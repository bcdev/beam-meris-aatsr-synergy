package org.esa.beam.synergy.operators;

import Jama.Matrix;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.esa.beam.synergy.util.math.Brent;
import org.esa.beam.synergy.util.math.Function;
import org.esa.beam.synergy.util.math.MvFunction;
import org.esa.beam.synergy.util.math.Powell;

/**
 * The AARDVARC class provides the main implementation of the Synergy aerosol
 * retrieval over land for single pixels.
 *
 * @author Andreas Heckel, Peter North
 * @version $Revision: 8034 $ $Date: 2010-01-20 14:47:34 +0000 (Mi, 20 Jan 2010) $
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
    private float[] wvlMeris;
    private float[] sza;   // [merisNadir, aatsrNadir, aatsrFward]
    private float[] saa;   // [merisNadir, aatsrNadir, aatsrFward]
    private float[] vza;   // [merisNadir, aatsrNadir, aatsrFward]
    private float[] vaa;   // [merisNadir, aatsrNadir, aatsrFward]
    private float surfPres;
    private float ndvi;
    private float[] specSoil;     // [wvlMeris]
    private float[] specVeg;      // [wvlMeris]
    private float[][][]   lutReflMeris;    // [wvl][alb][aot]
    private float[][][][] lutReflAatsr;    // [view][wvl][alb][aot]
    private float[] albDim;
    private float[] aotDim;
    private float optAOT;
    private float optErr;
    private float retrievalErr;
    private float angularWeight;
    private boolean doAATSR;
    private boolean doMERIS;
    private double[] pSpec;
    private boolean failed;
    private Powell powell;

    //for debugging
    private float[][] modelReflAatsr;
    private double[] pAng;

    /**
     * The constructor initializes the wavelength axes for MERIS and AATSR.
     *
     * @param wvlAatsr  - AATSR wavelengths
     * @param wvlMeris  - MERIS wavelengths
     */
    public Aardvarc(float[] wvlAatsr, float[] wvlMeris) {
        this.wvlAatsr = wvlAatsr;
        this.wvlMeris = wvlMeris;
        
        nAatsrChannels = wvlAatsr.length;
        nMerisChannels = wvlMeris.length;
        
        this.surfReflAatsr = new float[2][nAatsrChannels];
        this.surfReflMeris = new float[nMerisChannels];
        this.diffuseFraction = new float[2][nAatsrChannels];
        
        this.powell = new Powell();
        this.pSpec = new double[2];
        this.pAng = new double[6];
        this.failed = false;
}


    /**
     * This class provides the angle model function to be minimised by Powell.
     * (see ATBD (5), (6))
     *
     *
     */
    private class emodAng implements MvFunction {
        public boolean dump;

        public emodAng() {
            this.dump = false;
        }

        public emodAng(boolean dump) {
            this.dump = dump;
        }

        public double f(double[] p) {
            double[][] mval = new double[2][nAatsrChannels];
            //double[] weight = {1.5, 1.0, 0.2, 1.0};
            double[] weight = {1.5, 1.0, 0.5, 1.55};
            weight = normalize(weight);
            
            double DF = 1.0f;
            double gamma = 0.35f;
            double resid = 0.0f;
            double dir, g, dif, k;

            p[4]=0.5;

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
/*
            double llimit = 0.2;
            double ulimit = 1.5;
            for (int iview = 0; iview < 2; iview++) {
                if (p[nAatsrChannels+iview] < llimit) resid = resid + (p[nAatsrChannels+iview]-llimit) * (p[nAatsrChannels+iview]-llimit) * 1000;
                if (p[nAatsrChannels+iview] > ulimit) resid = resid + (p[nAatsrChannels+iview]-ulimit) * (p[nAatsrChannels+iview]-ulimit) * 1000;
            }
*/
            // Will Greys constraints from aatsr_aardvarc_4d
            if (p[0] < 0.01) resid=resid+(0.01-p[0])*(0.01-p[0])*1000.0;
            if (p[1] < 0.01) resid=resid+(0.01-p[1])*(0.01-p[1])*1000.0;
//            if (p[4] < 0.45 ) resid=resid+(0.2 -p[4])*(0.2 -p[4])*1000.0;
            if (p[5] < 0.2 ) resid=resid+(0.2 -p[5])*(0.2 -p[5])*1000.0;
//            if (p[4] > 0.55 ) resid=resid+(0.6 -p[4])*(0.6 -p[4])*1000.0;

            if (dump) dumpAatsrModelSpec(resid, p, mval);

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
            //double[] weight = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
            double[] weight = {1.0, 1.0, 1.0, 1.0, 0.2, 1.0, 1.0, 1.0, 0.05, 0.05, 0.05, 0.05, 0.05};
            weight = normalize(weight);
            double k;
            double resid = 0.0;
            for (int iwvl = 0; iwvl < nMerisChannels; iwvl++) {
                // mval: rho_spec_mod in ATBD (p. 22) (model function)
                if (p.length == 2) {
                    mval[iwvl] = p[0] * specVeg[iwvl] + p[1] * specSoil[iwvl];
                } else {
                    mval[iwvl] = p[0] * specVeg[iwvl] + p[1] * specSoil[iwvl] + p[2];
                }
                // difference to measurement:
                k = surfReflMeris[iwvl] - mval[iwvl];
                // residual:
                resid = resid + weight[iwvl] * k * k;
            }

            if (p[0] < 0.0) resid = resid + p[0] * p[0] * 1000;
            if (p[1] < 0.0) resid = resid + p[1] * p[1] * 1000;
            if (p.length > 2)
                if (p[2] < 0.0 || p[2] > 0.05) resid = resid + p[2] * p[2] * 1000;

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

        public boolean dump;

        public emodSyn() {
            this.dump = false;
        }

        public emodSyn(boolean dump) {
            this.dump = dump;
        }

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
/*
            //final float llimit = 0f;
            //final float penalty = 100000f;
            for (int iwvl = 0; iwvl < nAatsrChannels; iwvl++) {
                for (int iview = 0; iview < 2; iview++) {
                    if (surfReflAatsr[iview][iwvl] < llimit) {
                        fmin += (surfReflAatsr[iview][iwvl]-llimit) * (surfReflAatsr[iview][iwvl]-llimit) * penalty;
                    }
                }
            }
*/
            if (fmin <= 0.0f) {
                // initial vector p to start Powell optimization
                //double[] p = {0.1, 0.1, 0.1, 0.1, 0.5, 0.3};
                pAng[0] = 0.1; pAng[1] = 0.1; pAng[2] = 0.1; pAng[3] = 0.1;
                pAng[4] = 0.5; pAng[5] = 0.3;

                // defining unit matrix as base of the parameter space
                // needed for Powell
                double xi[][] = new double[pAng.length][pAng.length];
                for (int i = 0; i < pAng.length; i++) xi[i][i] = 1.0;

                double ftol = 0.5e-3;   // change of fmin below ftol defines the end of optimization

                powell.powell(pAng, xi, ftol, new emodAng(this.dump));
                fmin = (float) powell.fret;
            }
            else {
                //fmin += 1e-5;
                fmin += 1e-8;
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
            //final float llimit = 0f;
            //final float penalty = 10000f;
            for (int iwvl = 0; iwvl < nMerisChannels; iwvl++) {
                if (surfReflMeris[iwvl] < llimit) {
                    fmin += (surfReflMeris[iwvl]-llimit) * (surfReflMeris[iwvl]-llimit) * penalty;
                }
            }

            if (fmin <= 0.0f) {
                // initial vector p to start Powell optimization
                pSpec[0] = ndvi; pSpec[1] = 1.0-ndvi;
                if (pSpec.length > 2) pSpec[2] = 0.025;

                // defining unit matrix as base of the parameter space
                // needed for Powell
                double xi[][] = new double[pSpec.length][pSpec.length];
                for (int i = 0; i < pSpec.length; i++) xi[i][i] = 1.0;

                double ftol = 0.5e-2;   // change of fmin below ftol defines the end of optimization

                powell.powell(pSpec, xi, ftol, new emodSpec());
                fmin = (float) powell.fret;
            }
            else {
                //fmin += 1e-5;
                fmin += 1e-8;
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
            float[] weight = {angularWeight, 1.0f - angularWeight};

            if (tau < 1e-3) tau=1e-3;
            float fminAng = emodAngTau((float) tau);
            float fminSpec = emodSpecTau((float) tau);
            return (fminAng * weight[0] + fminSpec * weight[1]);
        }

    }


    private float calcRetrievalErr() {
        double[] x0 = {1, 1, 1};
        double[] x1 = {0.8*optAOT, optAOT, 0.6*optAOT};
        double[] x2 = {x1[0]*x1[0], x1[1]*x1[1], x1[2]*x1[2]};

        double optErrLow = new emodSyn().f(x1[0]);
        double optErrHigh = new emodSyn().f(x1[2]);
        double[][] y = {{optErrLow}, {optErr}, {optErrHigh}};
        double[][] xArr = {{x2[0], x1[0], x0[0]},{x2[1], x1[1], x0[1]},{x2[2], x1[2], x0[2]}};
        Matrix A = new Matrix(xArr);
        Matrix c = new Matrix(y);
        Matrix result = A.solve(c);
        double[][] resultArr = result.getArray();
        double a = resultArr[0][0]; // curvature term of parabola
        double b = resultArr[1][0]; // curvature term of parabola

        double retrievalError;
        if (a < 0) {
             retrievalError = Math.sqrt(optErr / 0.8 * 2 / 1e-4);
             failed = true;
        }
        else {
            retrievalError = Math.sqrt(optErr / 0.8 * 2 / a);
        }

        return (float) retrievalError;
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
    private float estimateDifFrac(float wvl, float aot, float sza, float pres) {

        // test if wavelength is in um or nm
        if (wvl > 100) wvl /= 1000;

        float diff_frac;
        if (aot < 0) diff_frac = 0.0f;
        else {
            double dmeff  = 0.55f;
            double ryfrac = 0.5f;
            double aerfrac = 0.75f;
            double k = -1.25f;
            double a = aot / Math.pow(0.55f, k);
            double pressure = pres;
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
        float[] toaAtTau = new float[albDim.length]; // contains toaRefl(albedo) interpolated to tau

        // find closest index iAot in aotDim corresponding to tau
        // values outside the range will be extrapolated linearly (which should not happen in general case)
        int iAot = aotDim.length - 1;
        if (tau > aotDim[aotDim.length-1]) {
            iAot--;
        }
        else if (tau < aotDim[0]) {
            iAot = 0;
        }
        else {
            while ((iAot >= 0) && (aotDim[iAot] >= tau)) iAot--;
            if (iAot == -1) iAot++; //happens only if tau == aotDim[0]
        }
        for (int j = 0; j < albDim.length; j++) {
            toaAtTau[j] = lutRefl[j][iAot] + (lutRefl[j][iAot + 1] - lutRefl[j][iAot]) / (aotDim[iAot + 1] - aotDim[iAot]) * (tau - aotDim[iAot]);
        }
        
        
        // find the albedo value corresponding to the given TOA reflectance
        int j = albDim.length - 1;
        if (toaAtTau[albDim.length-1] < toaRefl) {
            j--;
        }
        else if (toaAtTau[0] > toaRefl) {
            j = 0;
        }
        else {
            while ((j >= 0) && (toaAtTau[j] >= toaRefl)) j--;
            if (j == -1) j++; //happens only if toaRefl == a[0]
        }

        return albDim[j] + (albDim[j+1]-albDim[j]) / (toaAtTau[j+1]-toaAtTau[j]) * (toaRefl - toaAtTau[j]);
    }
/*
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
*/

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
                diffuseFrac[iView][iWvl] = estimateDifFrac(wvlAatsr[iWvl], tau, sza[1+iView], surfPres);
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

        // giving equal weighting in cases of dark dense vegetation (ndvi close to 1)
        // and angular only when ndiv < 0.5
        setAngularWeight();
        failed = false;
        
        Brent b = new Brent(0.0, 0.1, 2.0, new emodSyn(), 5e-4);
        optAOT = (float) b.getXmin();
        optErr = (float) b.getFx();
        retrievalErr = calcRetrievalErr();
    }

    private void setAngularWeight() {
        if (doAATSR && !doMERIS) {
            this.angularWeight = 1.0f;
        }
        else if (doMERIS && !doAATSR) {
            this.angularWeight = 0.0f;
        }
        else {
            float llimit = 0.1f;
            float ulimit = 0.7f;
            if (ndvi < llimit || ndvi > 1.0) {
                this.angularWeight = 1.0f;
            }
            else {
                float a = (0.5f - 1.0f) / (ulimit - llimit);
                this.angularWeight = (ndvi > ulimit) ? 0.5f : (1.0f + a * (ndvi - llimit));
            }
        }
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

    public void setSurfPres(float surfPres) {
        this.surfPres = surfPres;
    }

    public void setSza(float mSza, float aSzaN, float aSzaF) {
        this.sza = new float[3];
        sza[0] = mSza; sza[1] = aSzaN; sza[2] = aSzaF;
    }

    public void setSaa(float mSaa, float aSaaN, float aSaaF) {
        this.saa = new float[3];
        saa[0] = mSaa; saa[1] = aSaaN; saa[2] = aSaaF;
    }

    public void setVza(float mVza, float aVzaN, float aVzaF) {
        this.vza = new float[3];
        vza[0] = mVza; vza[1] = aVzaN; vza[2] = aVzaF;
    }

    public void setVaa(float mVaa, float aVaaN, float aVaaF) {
        this.vaa = new float[3];
        vaa[0] = mVaa; vaa[1] = aVaaN; vaa[2] = aVaaF;
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

    public float getRetrievalErr() {
        return retrievalErr;
    }

    public boolean isFailed() {
        return failed;
    }

    public void setDoAATSR(boolean doAATSR) {
        this.doAATSR = doAATSR;
    }

    public void setDoMERIS(boolean doMERIS) {
        this.doMERIS = doMERIS;
    }

    public void dumpParameter(String fname, float aot) {
        setAngularWeight();
        double f = new emodSyn().f(aot);
        optAOT = aot;
        optErr = (float) f;
        retrievalErr = calcRetrievalErr();
        dumpParameter(fname);
    }

    public void dumpParameter(String fname) {
        Locale orgLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        File fout = new File(fname);
        PrintWriter pw = null;

        try {
            pw = new PrintWriter(fout);
            pw.printf("********************************\n");
            pw.printf("* AARDVARC Dump\n");
            pw.printf("* NDVI:  %5.3f\n", this.ndvi);
            pw.printf("* AOT:   %5.3f\n", this.optAOT);
            pw.printf("* fmin:  %5.3f\n", this.optErr);
            pw.printf("* Error: %5.3f\n", this.retrievalErr);
            pw.printf("* p spec: %12.6f %12.6f", this.pSpec[0], this.pSpec[1]);
            if (pSpec.length > 2) pw.printf(" %12.6f\n", pSpec[2]);
            else pw.printf("\n");
            pw.printf("* Input Card for IDL version\n");
            pw.printf("* %6.3f %6.3f %6.3f %6.3f\n", sza[1], saa[1], vza[1], vaa[1]);
            pw.printf("* %6.3f %6.3f %6.3f %6.3f\n", sza[2], saa[2], vza[2], vaa[2]);
            pw.printf("* %6.3f %6.3f %6.3f %6.3f\n", sza[0], saa[0], vza[0], vaa[0]);
            pw.printf("*");
            for(int i=0; i<nAatsrChannels; i++) pw.printf(" %8.3f", toaReflAatsr[0][i]);
            pw.printf("\n*");
            for(int i=0; i<nAatsrChannels; i++) pw.printf(" %8.3f", toaReflAatsr[1][i]);
            pw.printf("\n*");
            for(int i=0; i<nMerisChannels; i++) pw.printf(" %8.3f", toaReflMeris[i]);
            pw.printf("\n");
            pw.printf("********************************\n");
            pw.printf("* X-Axis = MERIS Wavelength\n");
            pw.printf("* Y1-Axis = AATSR Wavelength\n");
            pw.printf("* Y2-Axis = TOA Reflec MERIS\n");
            pw.printf("* Y3-Axis = TOA Reflec AATSR Nadir\n");
            pw.printf("* Y4-Axis = TOA Reflec AATSR Fward\n");
            pw.printf("* Y5-Axis = SDR MERIS\n");
            pw.printf("* Y6-Axis = SDR AATSR Nadir\n");
            pw.printf("* Y7-Axis = SDR AATSR Fward\n");
            pw.printf("* Y8-Axis = original SoilSpec\n");
            pw.printf("* Y9-Axis = original VegSpec\n");
            pw.printf("* Y10-Axis = p * SoilSpec\n");
            pw.printf("* Y11-Axis = p * VegSpec\n");
            pw.printf("* Y12-Axis = p * Spec\n");
            pw.printf("********************************\n");

            String noDataS = "########";
            for (int i=0; i<nMerisChannels; i++) {
                if (i<nAatsrChannels) {
                    pw.printf("%8.3f %8.3f %f ", wvlMeris[i], wvlAatsr[i], toaReflMeris[i]);
                    pw.printf("%f %f %f ", toaReflAatsr[0][i], toaReflAatsr[1][i], surfReflMeris[i]);
                    pw.printf("%f %f %f ", surfReflAatsr[0][i], surfReflAatsr[1][i], specSoil[i]);
                    pw.printf("%f %f %f ", specVeg[i], pSpec[1]*specSoil[i], pSpec[0]*specVeg[i]);
                    double d = (pSpec[0]*specVeg[i]+pSpec[1]*specSoil[i]);
                    if (pSpec.length > 2) d += pSpec[2];
                    pw.printf("%f \n", d);
                } else {
                    pw.printf("%8.3f %s %f ", wvlMeris[i], noDataS, toaReflMeris[i]);
                    pw.printf("%s %s %f ", noDataS, noDataS, surfReflMeris[i]);
                    pw.printf("%s %s %f ", noDataS, noDataS, specSoil[i]);
                    pw.printf("%f %f %f ", specVeg[i], pSpec[1]*specSoil[i], pSpec[0]*specVeg[i]);
                    double d = (pSpec[0]*specVeg[i]+pSpec[1]*specSoil[i]);
                    if (pSpec.length > 2) d += pSpec[2];
                    pw.printf("%f \n", d);
                }
            }


        } catch (FileNotFoundException ex) {
            System.out.println(ex.getMessage());
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
        Locale.setDefault(orgLocale);
    }

    public float[] calcErrorMetric(float aot) {
        setAngularWeight();
        optAOT = aot;
        float fmin = (float)(new emodSyn(true).f(aot));
        float[] f = {aot, fmin, calcRetrievalErr()};
        return f;
    }

    public void dumpAatsrModelSpec(double resid, double[] p, double[][] mval) {
        Locale orgLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        String fname = String.format("p:/aardvarc_err_%2.0f", optAOT*100);
        File fout = new File(fname);
        PrintWriter pw = null;

        try {
            pw = new PrintWriter(fout);
            pw.printf("********************************\n");
            pw.printf("* file name: %s\n*\n", fname);
            pw.printf("* AARDVARC AATSR Model Spec\n");
            pw.printf("* AOT: %f\n", optAOT);
            pw.printf("* p: %f %f %f %f %f %f\n", pAng[0], pAng[1], pAng[2], pAng[3], pAng[4], pAng[5]);
            pw.printf("* resid: %e\n", resid);
            pw.printf("********************************\n");
            pw.printf("* X-Axis = WVL\n");
            pw.printf("* Y1-Axis = Model NAD\n");
            pw.printf("* Y2-Axis = Model FWD\n");
            pw.printf("* Y3-Axis = surf NAD\n");
            pw.printf("* Y4-Axis = surf FWD\n");
            pw.printf("********************************\n");

            for (int i=0; i<nAatsrChannels; i++) {
                pw.printf("%f %f %f %f %f\n", wvlAatsr[i], mval[0][i], mval[1][i], surfReflAatsr[0][i], surfReflAatsr[1][i]);
            }

        } catch (FileNotFoundException ex) {
            System.out.println(ex.getMessage());
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
        Locale.setDefault(orgLocale);
    }

    public void dumpErrorMetric(String fname) {
        Locale orgLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        File fout = new File(fname);
        PrintWriter pw = null;

        try {
            pw = new PrintWriter(fout);
            pw.printf("********************************\n");
            pw.printf("* file name: %s\n*\n", fname);
            pw.printf("* AARDVARC Error Metric\n");
            pw.printf("* NDVI:  %5.3f\n", this.ndvi);
            pw.printf("********************************\n");
            pw.printf("* X-Axis = aot\n");
            pw.printf("* Y1-Axis = fmin\n");
            pw.printf("* Y2-Axis = err\n");
            pw.printf("********************************\n");

            float[] erg;
            for (float tau=0.001f; tau<0.8; tau+=0.02) {
                erg = calcErrorMetric(tau);
                //modelReflAatsr = calcAatsrModel(pAng);
                //dumpAatsrModelSpec(tau, fname.replaceFirst(".dump", "_"+(int)(tau*100)+".dump"));

                pw.printf("%e %e %e\n", erg[0], erg[1], erg[2]);
            }

        } catch (FileNotFoundException ex) {
            System.out.println(ex.getMessage());
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
        Locale.setDefault(orgLocale);
    }

}
