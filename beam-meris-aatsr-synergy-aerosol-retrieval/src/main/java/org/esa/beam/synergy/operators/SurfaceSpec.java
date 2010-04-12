/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class reads surface spectra (wavelength/reflectance)
 *
 * @author Andreas Heckel
 * @version $Revision: 8041 $ $Date: 2010-01-20 17:23:15 +0100 (Mi, 20 Jan 2010) $
 */
public class SurfaceSpec {

    private float[] spec;

    public void setSpec(float[] spec) {
        this.spec = spec;
    }

    public float[] getSpec() {
        return spec;
    }

    public SurfaceSpec(String fname, float[] wvl) {

//        final InputStream inputStream = RetrieveAerosolLandOp.class.getResourceAsStream(fname);
        BufferedReader reader = null;

        float[] fullspec;
        float[] fullwvl;
        int i;
        try {
//            final InputStream inputStream = new FileInputStream(fname);
//            reader = new BufferedReader(new InputStreamReader(inputStream));
            reader = new BufferedReader(new FileReader(fname));
            String line;
            String[] stmp;
            fullspec = new float[100000];
            fullwvl = new float[100000];
            i = 0;
            spec = new float[wvl.length];
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!(line.isEmpty() || line.startsWith("#") || line.startsWith("*"))) {
                    stmp = line.split("[ \t]+");
                    fullwvl[i] = Float.valueOf(stmp[0]);
                    if (fullwvl[i] < 100) {
                        // assume wavelength given in um, convert to nm
                        fullwvl[i] *= 1000;
                    }
                    fullspec[i] = Float.valueOf(stmp[1]);
                    i++;
                }
            }
            for (int j = 0; j < wvl.length; j++) {
                spec[j] = interpol(fullspec, fullwvl, wvl[j], i);
            }
        } catch (IOException ex) {
            Logger.getLogger(SurfaceSpec.class.getName()).log(Level.SEVERE, "trying to open " + fname, ex);
        } finally {
            try {
                reader.close();
            } catch (IOException ex) {
                Logger.getLogger(SurfaceSpec.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

    }

    public static float interpol(float[] y, float[] x, float x0, int n) throws IllegalArgumentException {
        int jl, jm, ju;
        
        if ((x0<Math.min(x[0],x[n-1])) || (x0>Math.max(x[0], x[n-1]))) {
            throw new IllegalArgumentException("Interpolation Error: x0 outside range");
        }
        
        if (x0 == x[0]) return y[0];
        else if (x0 == x[n-1]) return y[n-1];

        jl = 0;
        ju = n+1;
        while (ju-jl > 1) {
            jm = (ju+jl) / 2;
            if ((x[n-1]>=x[0]) && (x0>=x[jm-1])) jl=jm;
            else if ((x[n-1]<x[0]) && (x0<x[jm-1])) jl=jm;
            else ju = jm;
        }
        jl--;
        
        return (y[jl+1]-y[jl])/(x[jl+1]-x[jl])*(x0-x[jl])+y[jl];
    }

}
