package org.esa.beam.synergy.operators;

import org.esa.beam.synergy.util.math.Brent;
import org.esa.beam.synergy.util.math.Function;
import org.esa.beam.synergy.util.math.MvFunction;
import org.esa.beam.synergy.util.math.Powell;

/**
 * The AARDVARC class provides the main implementation of the Synergy aerosol
 * retrieval over land for single pixels.
 *
 * @author Andreas Heckel, Peter North
 * @version $Revision: 8034 $ $Date: 2010-01-20 15:47:34 +0100 (Mi, 20 Jan 2010) $
 */
public class Aardvarc {

    private int nAatsrChannels;
    private int nMerisChannels;
    private float[][] diffuseFraction;   // [view][wvlAatsr]
    private float[][] surfReflAatsr;     // [view][wvlAatsr]
    private float[][] toaReflAatsr;      // [view][wvlAatsr]
    private float[] surfReflMeris;       // [wvlMeris]
    private float[] toaReflMeris;        // [wvlMeris]
    private float[] wvlAatsr;
    private float[] sza;   // [merisNadir, aatsrNadir, aatsrFward]

    private float ndvi;
    private float[] specSoil;     // [wvlMeris]
    private float[] specVeg;      // [wvlMeris]
    private float[][][]   lutReflMeris;    // [wvl][alb][aot]
    private float[][][][] lutReflAatsr;    // [view][wvl][alb][aot]
    private float[] albDim;
    private float[] aotDim;
    private float optAOT;
    private float optErr;
    private float angularWeight;
    private double[] pSpec;
    
    private Powell powell;

    /**
     * The constructor initializes the wavelength axes for MERIS and AATSR.
     *
     * @param wvlAatsr  - AATSR wavelengths
     * @param wvlMeris  - MERIS wavelengths
     */
    public Aardvarc(float[] wvlAatsr, float[] wvlMeris) {
        this.wvlAatsr = wvlAatsr;

        nAatsrChannels = this.wvlAatsr.length;
        nMerisChannels = wvlMeris.length;
        
        this.surfReflAatsr = new float[2][nAatsrChannels];
        this.surfReflMeris = new float[nMerisChannels];
        this.diffuseFraction = new float[2][nAatsrChannels];
        
        this.powell = new Powell();

        this.angularWeight = 0.5f;
        this.pSpec = new double[2];
}
    
    
    /**
     * This class provides the angle model function to be minimised by Powell.
     * (see ATBD (5), (6))
     *
     *
     */
    private class emodAng implements MvFunction {

        public double f(double[] p) {
            double[][] mval = new double[2][nAatsrChannels];
            double[] weight = {1.5, 1.0, 0.2, 1.0};
            weight = normalize(weight);
            
            double DF = 1.0f;
            double gamma = 0.35f;
            double resid = 0.0f;
            double dir, g, dif, k;
            
            for (int iwvl = 0; iwvl < nAatsrChannels; iwvl++){
                for (int iview = 0; iview < 2; iview++) {
                    dir = (1.0 - DF * diffuseFraction[iview][iwvl]) * p[nAatsrChannels+iview] * p[iwvl];
                    g   = (1.0 - gamma) * p[iwvl];
                    dif = (DF * diffuseFraction[iview][iwvl] 
                            + g * (1.0 - DF * diffuseFraction[iview][iwvl])) * gamma * p[iwvl] / (1.0 - g);
                    // mval: rho_spec_ang in ATBD (p. 23) (model function)
                    mval[iview][iwvl] = (dir + dif);
                    // difference to measurement:
                    k   = surfReflAatsr[iview][iwvl] - mval[iview][iwvl];
                    // residual:
                    resid = resid + weight[iwvl] * k * k;
                }
            }

            double llimit = 0.2;
            double ulimit = 1.5;
            for (int iview = 0; iview < 2; iview++) {
                if (p[nAatsrChannels+iview] < llimit) resid = resid + (p[nAatsrChannels+iview]-llimit) * (p[nAatsrChannels+iview]-llimit) * 1000;
                if (p[nAatsrChannels+iview] > ulimit) resid = resid + (p[nAatsrChannels+iview]-ulimit) * (p[nAatsrChannels+iview]-ulimit) * 1000;
            }
            
            return(resid);
        }

        public void g(double[] x, double[] g) throws UnsupportedOperationException {
            throw new UnsupportedOperationException("Not supported yet.");
        }
        
    }

    /**
     * This class provides the spectrum model function to be minimised by Powell.
     * (see ATBD (4), (6))
     *
     *
     */
    private class emodSpec implements MvFunction {

        public double f(double[] p) {
            double[] mval = new double[nMerisChannels];
            double[] weight = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
            weight = normalize(weight);
            double k;
            double resid = 0.0;
            for (int iwvl = 0; iwvl < nMerisChannels; iwvl++) {
                // mval: rho_spec_mod in ATBD (p. 22) (model function)
                mval[iwvl] = p[0] * specVeg[iwvl] + p[1] * specSoil[iwvl];
                // difference to measurement:
                k = surfReflMeris[iwvl] - mval[iwvl];
                // residual:
                resid = resid + weight[iwvl] * k * k;
            }

            if (p[0] < 0.0) resid = resid + p[0] * p[0] * 1000;
            if (p[1] < 0.0) resid = resid + p[1] * p[1] * 1000;

            return(resid);
        }

        public void g(double[] x, double[] g) throws UnsupportedOperationException {
            throw new UnsupportedOperationException("Not supported yet.");
        }
        
    }

    /**
     * This class provides the synergy error metric:
     *  E = alpha*E_ang + beta*E_spec
     *  with:
     *      - alpha, beta: weighting coefficients
     *      - E_ang: error of angular constraint
     *      - E_spec: error of spectral constraint
     */
    private class emodSyn implements Function {

        /**
         * This method provides E_ang for a given AOT
         *
         * @param tau   the AOT
         * @return  float
         */
        public float emodAngTau(float tau) {
            float fmin = 0;

            invLut(tau, lutReflAatsr, toaReflAatsr, surfReflAatsr, diffuseFraction);

            // inversion can lead to overcorrection of atmosphere
            // and thus to too small surface reflectances
            // defining a steep but smooth function guides the optimization 
            final float llimit = 5e-6f;
            final float penalty = 1000f;
            for (int iwvl = 0; iwvl < nAatsrChannels; iwvl++) {
                for (int iview = 0; iview < 2; iview++) {
                    if (surfReflAatsr[iview][iwvl] < llimit) {
                        fmin += (surfReflAatsr[iview][iwvl]-llimit) * (surfReflAatsr[iview][iwvl]-llimit) * penalty;
                    }
                }
            }

            if (fmin <= 0.0f) {
                // initial vector p to start Powell optimization
                double[] p = {0.1, 0.1, 0.1, 0.1, 0.5, 0.3};

                // defining unit matrix as base of the parameter space
                // needed for Powell
                double xi[][] = new double[p.length][p.length];
                for (int i = 0; i < p.length; i++) xi[i][i] = 1.0;

                double ftol = 0.5e-3;   // change of fmin below ftol defines the end of optimization

                powell.powell(p, xi, ftol, new emodAng());
                fmin = (float) powell.fret;
            }
            else {
                fmin += 1e-5;
            }

            return fmin;
        }

        /**
         * This method provides E_spec for a given AOT
         *
         * @param tau   the AOT
         * @return float
         */
        public float emodSpecTau(float tau) {
            float fmin = 0;

            invLut(tau, lutReflMeris, toaReflMeris, surfReflMeris);

            // inversion can lead to overcorrection of atmosphere
            // and thus to too small surface reflectances
            // defining a steep but smooth function guides the optimization 
            final float llimit = 5e-6f;
            final float penalty = 1000f;
            for (int iwvl = 0; iwvl < nMerisChannels; iwvl++) {
                if (surfReflMeris[iwvl] < llimit) {
                    fmin += (surfReflMeris[iwvl]-llimit) * (surfReflMeris[iwvl]-llimit) * penalty;
                }
            }

            if (fmin <= 0.0f) {
                // initial vector p to start Powell optimization
                pSpec[0] = ndvi; pSpec[1] = 1.0-ndvi;

                // defining unit matrix as base of the parameter space
                // needed for Powell
                double xi[][] = new double[pSpec.length][pSpec.length];
                for (int i = 0; i < pSpec.length; i++) xi[i][i] = 1.0;

                double ftol = 0.5e-2;   // change of fmin below ftol defines the end of optimization

                powell.powell(pSpec, xi, ftol, new emodSpec());
                fmin = (float) powell.fret;
            }
            else {
                fmin += 1e-5;
            }

            return fmin;
        }

        /**
         * This method computes E from E_ang and E_spec
         *
         * @param tau - AOT
         * @return float
         */
        public double f(double tau) {
        float[] weight = {angularWeight, 1.0f-angularWeight};
            float fminAng = emodAngTau((float) tau);
            float fminSpec = emodSpecTau((float) tau);
            return (fminAng * weight[0] + fminSpec * weight[1]);
        }
        
    }

    /**
     * This method computes the fraction of diffuse irradiance for given wavelength,
     * AOT and sun zenith angle.
     * (parameter 'D' in (5), ATBD p. 23)
     * TODO: clarify origin (reference!) of this parameter
     *
     * @param wvl   wavelength
     * @param aot   AOT
     * @param sza   sun zenith angle
     * @return  diff_frac
     */
    private float estimateDifFrac(float wvl, float aot, float sza) {
        float diff_frac;
        if (aot < 0) diff_frac = 0.0f;
        else {
            double dmeff  = 0.55f;
            double ryfrac = 0.5f;
            double aerfrac = 0.75f;
            double k = -1.25f;
            double a = aot / Math.pow(0.55f, k);
            double pressure = 1013.25f;
            double p0 = 1013.25f;
            double tau_rayl = (pressure / p0) / Math.cos(sza / 180 * Math.PI) *
                    (0.008569 * Math.pow(wvl, -4.0) * (1.0 + 0.0113 * Math.pow(wvl, -2.0) + 0.00013 * Math.pow(wvl, -4.0)));
            double tau_aero = dmeff * a * Math.pow(wvl, k) / Math.cos(sza/180*Math.PI);
            double tau_tot  = tau_rayl + tau_aero;

            double dir  = Math.exp(-1.0 * tau_tot);
            double diff = ryfrac * (1.0 - Math.exp(-1.0 * tau_rayl)) + aerfrac * (1.0 - Math.exp(-1.0 * tau_aero));

            diff_frac = (float) (diff / (dir + diff));
        }

        return diff_frac;
    }

    /**
     * This method provides the surface reflectance (in terms of an albedo value)
     * for given AOT and TOA reflectance from LUT.
     *
     * @param lutRefl - reflectance from LUT
     * @param tau - AOT
     * @param toaRefl - TOA reflectance
     * @return float
     */
    private float invInterpol(float[][] lutRefl, float tau, float toaRefl) {
        float[] a = new float[albDim.length];

        if ((tau > aotDim[aotDim.length-1]) || (tau < aotDim[0])) {
            return -1000.0f;
        }

        int iAot = aotDim.length - 1;
        while ((iAot >= 0) && (aotDim[iAot] >= tau)) iAot--;
        if (iAot == -1) iAot++; //happens only if tau == aotDim[0]
        for (int j = 0; j < albDim.length; j++) {
            a[j] = lutRefl[j][iAot] + (lutRefl[j][iAot+1] - lutRefl[j][iAot])
                      / (aotDim[iAot+1] - aotDim[iAot]) * (tau - aotDim[iAot]);
        }

        if ((a[albDim.length-1] < toaRefl) || (a[0] > toaRefl)) {
            return -1000.0f;
        }
        int j = albDim.length - 1;
        while ((j >= 0) && (a[j] >= toaRefl)) j--;
        if (j == -1) j++; //happens only if toaRefl == a[0]

        return albDim[j] + (albDim[j+1]-albDim[j]) / (a[j+1]-a[j]) * (toaRefl - a[j]);
    }

    /**
     * This method computes for MERIS the surface reflectance spectrum from the TOA reflectance spectrum
     * for a given AOT by using LUTs.
     *
     * @param tau       the AOT
     * @param lut       required 3D subsection of LUT
     * @param toaRefl   TOA reflectances
     * @param surfRefl  the computed surface reflectances
     */
    public void invLut(float tau, float[][][] lut, float[] toaRefl, float[] surfRefl) {
        for (int iWl = 0; iWl < nMerisChannels; iWl++){
            surfRefl[iWl] = invInterpol(lut[iWl], tau, toaRefl[iWl]);
        }
    }

    /**
     * This method computes for AATSR the surface reflectance spectrum from the TOA reflectance spectrum
     * for a given AOT by using LUTs.
     *
     * @param tau       the AOT
     * @param lut       required 4D subsection of LUT
     * @param toaRefl   TOA reflectances  (fward and nadir)
     * @param surfRefl  the computed surface reflectances (fward and nadir)
     * @param diffuseFrac - array for diffuse fraction estimates
     */
    public void invLut(float tau, float[][][][] lut, float[][] toaRefl, float[][] surfRefl, float[][] diffuseFrac) {
        for (int iView = 0; iView < 2; iView++){
            for (int iWvl = 0; iWvl < nAatsrChannels; iWvl++){
                surfRefl[iView][iWvl] = invInterpol(lut[iView][iWvl], tau, toaRefl[iView][iWvl]);
                diffuseFrac[iView][iWvl] = estimateDifFrac(wvlAatsr[iWvl], tau, sza[1+iView]);
            }
        }
    }
    public void invLut(float tau, float[][][][] lut, float[][] toaRefl, float[][] surfRefl) {
        for (int iView = 0; iView < 2; iView++){
            for (int iWvl = 0; iWvl < nAatsrChannels; iWvl++){
                surfRefl[iView][iWvl] = invInterpol(lut[iView][iWvl], tau, toaRefl[iView][iWvl]);
            }
        }
    }

    private double[] normalize(double[] fa) {
        float sum = 0;
        for (double f : fa) sum += f;
        for (int i=0; i<fa.length; i++) fa[i] /= sum;
        return fa;
    }

    /**
     * This method computes the optimal AOT at 550nm between 0.0 and 2.0
     * by using Brent's method.
     *
     */
    public void runAarvarc() {
        Brent b = new Brent(0.0, 0.1, 2.0, new emodSyn(), 5e-4);
        optAOT = (float) b.getXmin();
        optErr = (float) b.getFx();
    }

    public void setAlbDim(float[] albDim) {
        this.albDim = albDim;
    }

    public void setAotDim(float[] aotDim) {
        this.aotDim = aotDim;
    }

    public void setLutReflAatsr(float[][][][] lutReflAatsr) {
        this.lutReflAatsr = lutReflAatsr;
    }

    public void setLutReflMeris(float[][][] lutReflMeris) {
        this.lutReflMeris = lutReflMeris;
    }

    public void setNdvi(float ndvi) {
        this.ndvi = ndvi;
    }

    public void setSpecSoil(float[] specSoil) {
        this.specSoil = specSoil;
    }

    public void setSpecVeg(float[] specVeg) {
        this.specVeg = specVeg;
    }

    public void setSza(float mSza, float aSzaN, float aSzaF) {
        this.sza = new float[3];
        sza[0] = mSza; sza[1] = aSzaN; sza[2] = aSzaF;
    }

    public void setToaReflAatsr(float[][] toaReflAatsr) {
        this.toaReflAatsr = toaReflAatsr;
    }

    public void setToaReflMeris(float[] toaReflMeris) {
        this.toaReflMeris = toaReflMeris;
    }

    public float getOptAOT() {
        return optAOT;
    }

    public float getOptErr() {
        return optErr;
    }

}
