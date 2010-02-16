package org.esa.beam.synergy.operators;

import com.bc.jnn.JnnException;
import com.bc.jnn.JnnNet;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.synergy.util.GlintHelpers;
import org.esa.beam.util.logging.BeamLogManager;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class providing the solar part computation for Glint retrieval.
 *
 * @author Olaf Danne
 * @version $Revision: 8092 $ $Date: 2010-01-26 19:08:16 +0100 (Di, 26 Jan 2010) $
 */
public class GlintSolarPart37 {
    private float[][] aCoeff37;
    private float[][] hCoeff37;
    private float[] hWeight37;
    private float[][] aCoeff16;
    private float[][] hCoeff16;
    private float[] hWeight16;

    private JnnNet neuralNetWv;

    private Logger logger;
    private double[] tempFromTable;
    private double[] radianceFromTable;

    private static final float WATER_VAPOUR_STANDARD_VALUE = 2.8f;

    public GlintSolarPart37() {
        logger = BeamLogManager.getSystemLogger();

        try {
            tempFromTable = GlintAuxData.getInstance().createTemp2RadianceTable().getTemp();
            radianceFromTable = GlintAuxData.getInstance().createTemp2RadianceTable().getRad();
        } catch (IOException e) {
             throw new OperatorException("Failed to read BT to radiance conversion table:\n" + e.getMessage(), e);
        }
    }

    /**
     *  This method loads required Glint Auxdata
     *
     * @throws IOException
     * @throws JnnException
     */
    protected void loadGlintAuxData() throws IOException, JnnException {
        aCoeff37 = GlintAuxData.getInstance().readWaterVapourCoefficients(37, "A");
        hCoeff37 = GlintAuxData.getInstance().readWaterVapourCoefficients(37, "H");
        aCoeff16 = GlintAuxData.getInstance().readWaterVapourCoefficients(16, "A");
        hCoeff16 = GlintAuxData.getInstance().readWaterVapourCoefficients(16, "H");

        hWeight37 = GlintAuxData.getInstance().readTransmissionWeights(37, "H");
        hWeight16 = GlintAuxData.getInstance().readTransmissionWeights(16, "H");

        neuralNetWv = GlintAuxData.getInstance().loadNeuralNet(GlintAuxData.NEURAL_NET_WV_OCEAN_MERIS_FILE_NAME);
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
     * This method computes the water vapour column to correct for transmission in 3.7um (and 1.6um) channel..
     * Computation by FUB neural net (IDL breadboard step 1.b.1)
     *
     * @param zonalWind - zonalWind
     * @param meridionalWind - meridionalWind
     * @param merisAzimuthDifference - MERIS azimuth difference
     * @param merisViewZenith - MERIS view zenith angle (degree)
     * @param merisSunZenith - MERIS sun zenith angle (degree)
     * @param merisRadiance14 - MERIS radiance band14 angle (degree)
     * @param merisRadiance15 - MERIS radiance band15 angle (degree)
     * @return float
     */
    protected float computeWaterVapour(float zonalWind, float meridionalWind,
                                       float merisAzimuthDifference,
                                     float merisViewZenith, float merisSunZenith,
                                     float merisRadiance14, float merisRadiance15) {
        float waterVapour = WATER_VAPOUR_STANDARD_VALUE;   // standard value

        final double[] nnIn = new double[5];
        final double[] nnOut = new double[1];

        double windSpeed = Math.sqrt(zonalWind*zonalWind + meridionalWind*meridionalWind);

        // apply FUB NN...
        nnIn[0] = windSpeed;
        nnIn[1] = Math.cos(Math.toRadians(merisAzimuthDifference))*
                  Math.sin(Math.toRadians(merisViewZenith));  // angles in degree!
        nnIn[2] = Math.cos(Math.toRadians(merisViewZenith));  // angle in degree!
        nnIn[3] = Math.cos(Math.toRadians(merisSunZenith));  // angle in degree!
        nnIn[4] = Math.log(Math.max(merisRadiance15, 1.0E-4)/Math.max(merisRadiance14, 1.0E-4));

        float[][] nnLimits = new float[][]{{3.75e-02f, 1.84e+01f},
                                           {-6.33e-01f, 6.31e-01f},
                                           {7.73e-01f, 1.00e+00f},
                                           {1.60e-01f, 9.26e-01f},
                                           {-6.98e-01f, 7.62e+00f}};

        for (int i=0; i<nnIn.length; i++) {
            if (nnIn[i] >= nnLimits[i][0] && nnIn[i] >= nnLimits[i][1]) {
                // otherwise do not apply NN, keep WV to standard value
                neuralNetWv.process(nnIn, nnOut);
                waterVapour = (float) nnOut[0];
            }
        }

        return waterVapour;
    }

    /**
     *  This method computes the transmission in 3.7um (and 1.6um) channel..
     *  Computation by wieghted sum of k-terms (IDL breadboard step 1.b.2)
     *
     * @param channel - the channel
     * @param waterVapourColumn - waterVapourColumn
     * @param aatsrSunElevation - AATSR sun elevation angle (degree)
     * @param aatsrViewElevation - AATSR view elevation angle (degree)
     * @return float
     */
    protected float computeTransmissionOld(int channel, float waterVapourColumn,
                                     float aatsrSunElevation, float aatsrViewElevation) {
        float transmission = 1.0f;

        double am = 1.0/Math.cos(Math.toRadians(aatsrSunElevation)) + 1.0/Math.cos(Math.toRadians(aatsrViewElevation));

        if (channel == 37) {
            final int numLayers = aCoeff37[0].length;
            final int numSpectralIntervals = aCoeff37.length;
            double weightedIntegral = 0.0;
            for (int i=0; i<numSpectralIntervals; i++) {
                double layerIntegral = 0.0f;
                for (int j=0; j<numLayers; j++) {
                    double sumCoeffsWv = aCoeff37[i][j] + hCoeff37[i][j] * waterVapourColumn / 2.7872;
                    layerIntegral += sumCoeffsWv;
                }
                weightedIntegral += hWeight37[i] * Math.exp(-am * layerIntegral);
            }
            transmission = (float) weightedIntegral;
        } else if (channel == 16) {
            final int numLayers = aCoeff16[0].length;
            final int numSpectralIntervals = aCoeff16.length;
            double weightedIntegral = 0.0f;
            for (int i=0; i<numSpectralIntervals; i++) {
                double layerIntegral = 0.0f;
                for (int j=0; j<numLayers; j++) {
                    double sumCoeffsWv = aCoeff16[i][j] + hCoeff16[i][j] * waterVapourColumn / 2.7872;
                    layerIntegral += sumCoeffsWv;
                }
                weightedIntegral += hWeight16[i] * Math.exp(-am * layerIntegral);
            }
            transmission = (float) weightedIntegral;
        }  else {
            logger.log(Level.ALL,
                        "Wrong channel " + channel + " provided to 'computeTransmission' - transmission kept to zero.");
        }
        return transmission;
    }

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
     * @param aatsrTrans37 - AATSR 370nm transmission
     * @return float
     */
    protected float computeSolarPartOld(float aatsrRad37, float aatsrThermalPart37, float aatsrTrans37) {
        return ( (aatsrRad37 - aatsrThermalPart37)/aatsrTrans37 ); // in 1/sr
    }
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

    /**
     * This method converts the solar part at TOA to 'AATSR' units
     * (IDL breadboard step 1.d)
     *
     * @param solarPart - TOA solar part
     * @param aatsrSunElevation  - AATSR sun elevation angle (degree)
     * @return float
     */
    protected float convertToAatsrUnits(float solarPart, float aatsrSunElevation) {
        return ( (float) (solarPart*Math.PI*100.0/
                Math.cos(Math.toRadians(90.0-aatsrSunElevation))) );
    }
}


