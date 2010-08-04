package org.esa.beam.synergy.operators;

import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.synergy.util.GlintHelpers;

import java.io.IOException;

/**
 * Class providing the solar part computation for FUB Glint retrieval.
 *
 * @author Olaf Danne
 * @version $Revision: 8092 $ $Date: 2010-01-26 19:08:16 +0100 (Di, 26 Jan 2010) $
 */
public class GlintSolarPart37 {

    private final double[] tempFromTable;
    private final double[] radianceFromTable;

    public GlintSolarPart37() {
        try {
            tempFromTable = GlintAuxData.getInstance().createTemp2RadianceTable().getTemp();
            radianceFromTable = GlintAuxData.getInstance().createTemp2RadianceTable().getRad();
        } catch (IOException e) {
             throw new OperatorException("Failed to read BT to radiance conversion table:\n" + e.getMessage(), e);
        }
    }

    /**
     * This method computes the thermal part of radiance in 3.7um channel (IDL breadboard step 1.a)
     *
     * @param aa11 - parameter
     * @param aa12 - parameter
     * @return float
     */
    protected float extrapolateTo37(float aa11, float aa12) {
        final float[] par = new float[] {4.91348f, 0.978489f, 1.37919f};

        return ( par[0] + par[1]*aa11 + par[2]*(aa11-aa12) );
    }

    /**
     *  This method computes the transmission in 3.7um (and 1.6um) channel..
     *  Computation by wieghted sum of k-terms (IDL breadboard step 1.b.2)
     */
    protected float[] computeTransmission(double meris14, double meris15) {
        final double[] coeff= new double[]{0.0655246,0.654369};
        final double[] errCoeff= new double[]{Math.sqrt(0.00103253),Math.sqrt(0.00186558)};
        double transmission = 0.0;
        double error = 0.0;
        if (meris14 != 0.0 && meris15 != 0.0 && meris15 < meris14){
            transmission = Math.exp(-(coeff[0]-coeff[1]*Math.log(meris15/meris14)));
            error = Math.exp(-((coeff[0]+errCoeff[0])-(coeff[1]+errCoeff[1])*Math.log(meris15/meris14)));

        }
        float[] transmissionInfo = new float[2];
        transmissionInfo[0] = (float) transmission;
        transmissionInfo[1] = (float) error;

        return transmissionInfo;
    }

    /**
     * This method converts the units of 3.7um from BT(K) to real normalized radiance units (1/sr).
     * Computation by interpolation (IDL breadboard step 1.c)
     *
     * @param brightnessTemp - brightness temperature
     * @return float
     */
    protected float convertBT2Radiance(float brightnessTemp) {
        float radiance = 0.0f;

        final int index = GlintAuxData.getInstance().getNearestTemp2RadianceTableIndex(brightnessTemp, tempFromTable);
        if (index >= 0 && index < radianceFromTable.length-1) {
            radiance = (float) GlintHelpers.linearInterpol(brightnessTemp, tempFromTable[index],
                tempFromTable[index+1], radianceFromTable[index], radianceFromTable[index+1]);
        }

        return radiance;
    }

    /**
     * This method converts the difference between the measured and the thermal part,
     * which is the solar part at TOA.
     * Correction for transmission --> specular reflection (IDL breadboard step 1.d)
     *
     * @param aatsrRad37 - AATSR 370nm radiance
     * @param aatsrThermalPart37 - AATSR 370nm thermal part
     * @param aatsrTrans37Info - AATSR 370nm transmission
     * @return float
     */
    protected float[] computeSolarPart(float aatsrRad37, float aatsrThermalPart37, float[] aatsrTrans37Info) {
        float[] aatsrRad37Info = new float[2];

        float aatsrRad37TempError = (aatsrRad37 - aatsrThermalPart37 + 0.25f) / aatsrTrans37Info[0];
        final float aatsrRad37Norm = (aatsrRad37 - aatsrThermalPart37) / aatsrTrans37Info[0];
        final float aatsrRad37TransError = aatsrRad37Norm - (aatsrRad37 - aatsrThermalPart37) / aatsrTrans37Info[1];
        final float aatsrRad37Error = (float) (Math.sqrt(aatsrRad37TempError * aatsrRad37TempError +
                aatsrRad37TransError * aatsrRad37TransError));

        aatsrRad37Info[0] = aatsrRad37Norm;
        aatsrRad37Info[1] = aatsrRad37Error;
        
        return aatsrRad37Info; // in 1/sr
    }
}


