/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.util.math.LookupTable;

/**
 * Class representing a reflectance LUT for land aerosol / SDR retrieval
 *
 * @author Andreas Heckel
 * @version $Revision: 8034 $ $Date: 2010-01-20 14:47:34 +0000 (Mi, 20 Jan 2010) $
 */
public class ReflectanceBinLUT {
    private LookupTable[] toaMERIS;
    private LookupTable[] toaAATSR;
    private float[] szaDim;
    private float[] raziDim;
    private float[] vzaDim;
    private float[] albDim;
    private float[] aotDim;
    private float[] presDim;
    private float[] logPresDim;
    private int aerosolModel;

    public ReflectanceBinLUT(String lutPath, int aerosolModelID, float[] merisWvl, float[] aatsrWvl) throws OperatorException {
        float lutWvl;
        int iWvl;

        if (!lutPath.endsWith(File.separator)) {
            lutPath += File.separator;
        }

        //
        // read MERIS LUT
        //
        String lutFileName;
        String lutFileNameStub = lutPath + "MERIS" + File.separator;
        lutFileNameStub += "MERIS_%WVLSTR%_" + String.format("%02d", aerosolModelID);

        // if Dimensions not already read, read Dimensions of LUT first
        if (szaDim == null) readLutDims(lutPath + "lutDimensions.asc");
        
        // read the actual LUT (simulated Top Of Atmosphere Radiances)
        // for all wavelength
        toaMERIS = new LookupTable[merisWvl.length];
        for (String ws : RetrieveAerosolConstants.LUT_LAND_MERIS_WAVELEN) {
            lutFileName = lutFileNameStub.replaceAll("%WVLSTR%", ws);
            lutWvl = Float.valueOf(ws);
            iWvl = findWvlIndex(lutWvl, merisWvl);
            toaMERIS[iWvl] = readToaRad(lutFileName);
        }

        //
        // read AATSR LUT
        //
        lutFileNameStub = lutPath + "AATSR" + File.separator;
        lutFileNameStub += "AATSR_%WVLSTR%_" + String.format("%02d", aerosolModelID);
        
        // read the actual LUT (simulated Top Of Atmosphere Radiances)
        // for all wavelength
        toaAATSR = new LookupTable[aatsrWvl.length];
        for (String ws : RetrieveAerosolConstants.LUT_LAND_AATSR_WAVELEN) {
            lutFileName = lutFileNameStub.replaceAll("%WVLSTR%", ws);
            lutWvl = Float.valueOf(ws);
            iWvl = findWvlIndex(lutWvl, aatsrWvl);
            toaAATSR[iWvl] = readToaRad(lutFileName);
        }
        aerosolModel = aerosolModelID;

    }

    public int getAerosolModel() {
        return aerosolModel;
    }

    public float[] getAlbDim() {
        return albDim;
    }

    public float[] getLogPresDim() {
        return logPresDim;
    }

    public float[] getPresDim() {
        return presDim;
    }

    public float[] getRaziDim() {
        return raziDim;
    }

    public float[] getSzaDim() {
        return szaDim;
    }

    public LookupTable[] getToaAATSR() {
        return toaAATSR;
    }

    public LookupTable[] getToaMERIS() {
        return toaMERIS;
    }

    public float[] getVzaDim() {
        return vzaDim;
    }

    public float[] getAotDim() {
        return aotDim;
    }

    public void subsecLUT(String instr, float pres, float o3, float vza, float vaa, float sza, float saa, float[] wvl, float[][][] a) {
        int nWl = wvl.length;
        int nAot = aotDim.length;
        int nAlb = albDim.length;
        boolean isAatsr = instr.equals("aatsr");
        boolean isMeris = instr.equals("meris");
        
        float relAzi = Math.abs(saa - vaa);
        relAzi = (relAzi > 180.0f) ? 180 - (360 - relAzi) : 180 - relAzi;
        
        double geomAMF = (1/Math.cos(Math.toRadians(sza))+1/Math.cos(Math.toRadians(vza)))/2;
        double rad2rfl = Math.PI / Math.cos(Math.toRadians(sza));
        double o3Corr = 1.0;
        double wvCorr = 1.0;
        final double wvCol = 2.0; // constant water vapour column g/cm^2
        
        for (int iWl = 0; iWl < nWl; iWl++){
            if (isAatsr) {
                o3Corr = Math.exp(o3 / 1000 * RetrieveAerosolConstants.o3CorrSlopeAatsr[iWl] * geomAMF);
                wvCorr = Math.exp(wvCol*RetrieveAerosolConstants.wvCorrSlopeAatsr[iWl]);
            } else if (isMeris) {
                o3Corr = Math.exp(o3 / 1000 * RetrieveAerosolConstants.o3CorrSlopeMeris[iWl] * geomAMF);
                wvCorr = Math.exp(wvCol*RetrieveAerosolConstants.wvCorrSlopeMeris[iWl]);
            }
            for (int iAlb = 0; iAlb < nAlb; iAlb++) {
                for (int iAot = 0; iAot < nAot; iAot++) {
                    double[] x0 = {Math.log(pres), (double)vza, (double)relAzi, (double)sza,
                                    (double)aotDim[iAot], (double) albDim[iAlb]};
                    if (isMeris) {
                        a[iWl][iAlb][iAot] = (float) (rad2rfl * toaMERIS[iWl].getValue(x0));
                    } else if (isAatsr){
                        a[iWl][iAlb][iAot] = (float) (rad2rfl * toaAATSR[iWl].getValue(x0));
                    }
                    a[iWl][iAlb][iAot] *= o3Corr ;
                    a[iWl][iAlb][iAot] *= wvCorr;
                }
            }
        }
    }
    
    private int findWvlIndex(float lutWvl, float[] merisWvl) {
        int iWvl = 0;
        for (int i = 0; i < merisWvl.length; i++) {
            if (Math.abs(lutWvl - merisWvl[i]) < Math.abs(lutWvl - merisWvl[iWvl])) {
                iWvl = i;
            }
        }
        return iWvl;
    }

    private void readLutDims(String lutFileName) {
        BufferedReader inF = null;
        try {
            inF = new BufferedReader(new FileReader(lutFileName));
            String line;
            while ((line = inF.readLine()) != null) {
                String[] stmp = line.trim().split("[ ]+");
                if (stmp[0].toUpperCase().startsWith("PRES")) {
                    presDim = new float[stmp.length-2];
                    logPresDim = new float[stmp.length-2];
                    for (int i=2; i<stmp.length; i++) {
                        presDim[i-2] = Float.valueOf(stmp[i]);
                        logPresDim[i-2] = (float) Math.log(presDim[i-2]);
                    }
                }
                if (stmp[0].toUpperCase().startsWith("VIEWZ")) {
                    vzaDim = new float[stmp.length-2];
                    for (int i=2; i<stmp.length; i++) vzaDim[i-2] = Float.valueOf(stmp[i]);
                }
                if (stmp[0].toUpperCase().startsWith("RELAZ")) {
                    raziDim = new float[stmp.length-2];
                    for (int i=2; i<stmp.length; i++) raziDim[i-2] = Float.valueOf(stmp[i]);
                }
                if (stmp[0].toUpperCase().startsWith("SOLARZ")) {
                    szaDim = new float[stmp.length-2];
                    for (int i=2; i<stmp.length; i++) szaDim[i-2] = Float.valueOf(stmp[i]);
                }
                if (stmp[0].toUpperCase().startsWith("AOT")) {
                    aotDim = new float[stmp.length-2];
                    for (int i=2; i<stmp.length; i++) aotDim[i-2] = Float.valueOf(stmp[i]);
                }
                if (stmp[0].toUpperCase().startsWith("ALBE")) {
                    albDim = new float[stmp.length-2];
                    for (int i=2; i<stmp.length; i++) albDim[i-2] = Float.valueOf(stmp[i]);
                }
            }
        } catch (IOException ex) {
            throw new OperatorException("Could not read LUTs.");
//            Logger.getLogger(ReflectanceBinLUT.class.getName()).log(Level.SEVERE, "trying to open " + lutFileName, ex);
        } finally {
            try {
                if (inF != null) {
                    inF.close();
                }
            } catch (IOException ex) {
                Logger.getLogger(ReflectanceBinLUT.class.getName()).log(Level.SEVERE, "trying to close " + lutFileName, ex);
            }
        }
    }

    private LookupTable readToaRad(String lutFileName) throws OperatorException {
        ObjectInputStream toaFile = null;
        LookupTable lut = null;
        try {

            toaFile = new ObjectInputStream(new FileInputStream(lutFileName));

            int n = presDim.length*vzaDim.length*raziDim.length*szaDim.length*aotDim.length*albDim.length;
            float[] a = (float[]) toaFile.readObject();
            if (a.length != n) throw new OperatorException("Size of LUT array not equal size of Dimensions.");
            lut = new LookupTable(a, logPresDim, vzaDim, raziDim, szaDim, aotDim, albDim);
            //System.out.println(lutFileName);
/*
            double[] x0 = {Math.log(800), 87.14, 180.0, 0.0, 0.0, 0.0};
            int i = 0 + 0*13 + 0*13*9 + 18*13*9*17 + 16*13*9*17*19 + 0*13*9*17*19*17;
            System.out.printf(Locale.ENGLISH, "%12.9f %12.9f %12.9f \n%12.9f %12.9f %12.9f \n %12.9f / %12.9f\n",
                    logPresDim[0],vzaDim[0],raziDim[0],szaDim[0],aotDim[0],albDim[0],
                    a[i], lut.getValue(x0));
 */
            
        } catch (Exception ex1) {
            String mess = "Could not open LUT file: \n" + ex1.getMessage();
            //Logger.getLogger(ReflectanceBinLUT.class.getName()).log(Level.SEVERE, mess, ex);
            throw new OperatorException(mess, ex1);
        } finally {
            try {
                if (toaFile != null) toaFile.close();
            } catch (Exception ex2) {
                String mess = "trying to close " + lutFileName;
                //Logger.getLogger(ReflectanceBinLUT.class.getName()).log(Level.SEVERE, "trying to close " + lutFileName, ex);
                throw new OperatorException(mess, ex2);
            }
        }
        return lut;
    }
    
}
