package org.esa.beam.synergy.operators;

import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.synergy.util.GlintHelpers;
import org.jfree.data.statistics.Regression;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.Calendar;

/**
 * Class providing utility methods for preparation of Glint retrieval
 *
 * @author Olaf Danne
 * @version $Revision: 8064 $ $Date: 2010-01-21 18:19:59 +0100 (Do, 21 Jan 2010) $
 */
public class GlintPreparation {

    public GlintPreparation() {
    }

    /**
     * This method provides the day of year for given date string
     *
     * @param yyyymmdd - date string
     * @return  int
     */
    protected static int getDayOfYear(String yyyymmdd) {
        Calendar cal = Calendar.getInstance();
        int doy = -1;
        try {
            final int year = Integer.parseInt(yyyymmdd.substring(0, 4));
            final int month = Integer.parseInt(yyyymmdd.substring(4, 6)) - 1;
            final int day = Integer.parseInt(yyyymmdd.substring(6, 8));
            cal.set(year, month, day);
            doy = cal.get(Calendar.DAY_OF_YEAR);
        } catch (StringIndexOutOfBoundsException e) {
            e.printStackTrace();
        }  catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return doy;
    }

    /**
     *  This method computes the solar irradiance in 3.7um channel.
     *  Computation by interpolation and integration.
     *
     * @param dayOfYear - day of year
     * @return float
     */
    protected static float computeSolarIrradiance37(int dayOfYear) {
        float solarIrradiance37;

        double[] wlSpectralResponse;
        double[] spectralResponse;
        double[] sox;
        double[] soy;

        try {
            wlSpectralResponse = GlintAuxData.getInstance().createAatsrSpectralResponse37Table().getWavelength();
            spectralResponse = GlintAuxData.getInstance().createAatsrSpectralResponse37Table().getResponse();
        } catch (IOException e) {
            throw new OperatorException("Failed to read spectral response table:\n" + e.getMessage(), e);
        }

        try {
            sox = GlintAuxData.getInstance().createCahalanTable().getX();
            soy = GlintAuxData.getInstance().createCahalanTable().getY();
        } catch (IOException e) {
            throw new OperatorException("Failed to read Cahalan table:\n" + e.getMessage(), e);
        }

        double normFactor = 0.0d;
        for (int i=0; i< spectralResponse.length-1; i+=2) {
            final double h = wlSpectralResponse[i+2] - wlSpectralResponse[i];
            normFactor += GlintAuxData.getInstance().getSimpsonIntegral(spectralResponse[i], spectralResponse[i+1], spectralResponse[i+2], h);
        }

        double ra = 0.0d;
        for (int i=0; i< spectralResponse.length-1; i+=2) {
            final int index = GlintAuxData.getInstance().getNearestCahalanTableIndex(wlSpectralResponse[i]*1000.0, sox);
            final double soi = GlintHelpers.linearInterpol(wlSpectralResponse[i], sox[index]/1000.0, sox[index+1]/1000.0, soy[index], soy[index+1]);
            final double h = wlSpectralResponse[i+2] - wlSpectralResponse[i];
            ra += GlintAuxData.getInstance().getSimpsonIntegral(spectralResponse[i]*soi, spectralResponse[i+1]*soi, spectralResponse[i+2]*soi, h);
        }
        ra /= normFactor;
        final double rsun = 1.0 - 0.01673*Math.cos(Math.toRadians(0.9856*((float)dayOfYear - 2.0)));
        solarIrradiance37 = (float) (ra*10.0/(rsun*rsun));

        return solarIrradiance37;
    }

    /**
     * This method removes ambiguities in azimuth differences.
     *
     * @param viewAzimuth - view azimuth angle (degree)
     * @param sunAzimuth - sun azimuth angle (degree)
     * @return float
     */
    protected static float removeAzimuthDifferenceAmbiguity(float viewAzimuth, float sunAzimuth) {
        float correctedViewAzimuth = viewAzimuth;
        float correctedSunAzimuth = sunAzimuth;

        // first correct for angles < 0.0
        if (correctedViewAzimuth < 0.0) {
            correctedViewAzimuth += 360.0;
        }
        if (correctedSunAzimuth < 0.0) {
            correctedSunAzimuth += 360.0;
        }

        // now correct difference ambiguities
        float correctedAzimuthDifference = correctedViewAzimuth - correctedSunAzimuth;
        if (correctedAzimuthDifference > 180.0) {
            correctedAzimuthDifference = 360.0f - correctedAzimuthDifference;
        }
        if (correctedAzimuthDifference < 0.0) {
            correctedAzimuthDifference = -1.0f* correctedAzimuthDifference;
        }
        return correctedAzimuthDifference;
    }

    /**
     *  This method limits the processing to pixels which are:
     *  - cloud free or glint-effected (this is how the AATSR cloud mask is organized)
     *  - inside AATSR FOV
     *  - not saturated at 3.7um, no ice, no cloud.
     *
     * @param aatsrCloudFlagNadirLand - AATSR cloud flag nadir: LAND
     * @param aatsrCloudFlagNadirCloudy - AATSR cloud flag nadir: CLOUD
     * @param aatsrCloudFlagNadirGlint - AATSR cloud flag nadir: GLINT
     * @param aatsrViewElevation - AATSR view elevation
     * @param aatsrBT37 - AATSR 370nm brightness temperature
     * @return  boolean
     */
    protected static boolean isUsefulPixel(boolean aatsrCloudFlagNadirLand,
                                    boolean aatsrCloudFlagNadirCloudy,
                                    boolean aatsrCloudFlagNadirGlint,
                                    float aatsrViewElevation, float aatsrBT37) {
        // todo: check if 'Cloudy' and 'Glint' flags shall be used
        // (currently deactivated as in FLINT)
        return ( !aatsrCloudFlagNadirLand &&
//                          (!aatsrCloudFlagNadirCloudy || aatsrCloudFlagNadirGlint) &&
                          (aatsrViewElevation > 0.0) && (aatsrBT37 > 270.0) );
    }

    /**
     *
     * This method reestablishes the viewing azimuth discontinuity at nadir.
     * Computation by first order polynominal fit on 'good' pixel left and right
     * of sub-satellite point.
     * A method like this should be integrated in BEAM later.
     * Discuss other choices of fitting (second order as in breadboard?)
     *
     * @param viewAzimuthRaster  - va input tile
     * @param rect - underlying rectangle
     */
    public static void correctViewAzimuthLinear(Tile viewAzimuthRaster, Rectangle rect) {

        double[] yArray;
        for (int y=0; y<rect.height; y++) {
           int startIndex = 0;
           int endIndex = rect.width-1;

           //
           for (int x=1; x<rect.width; x++) {
               if (viewAzimuthRaster.getSampleDouble(x, y) != 0.0 &&
                   viewAzimuthRaster.getSampleDouble(x-1, y) == 0.0) {
                   startIndex = x;
                   break;
               }
           }

           for (int x=0; x<rect.width-1; x++) {
               if (viewAzimuthRaster.getSampleDouble(x, y) != 0.0 &&
                   viewAzimuthRaster.getSampleDouble(x+1, y) == 0.0) {
                   endIndex = x;
                   break;
               }
           }

           int arrayLength = endIndex - startIndex + 1;

           if (startIndex < endIndex)  {
               // if not, no correction is needed
               yArray = new double[arrayLength];

               for (int x=startIndex; x<=endIndex; x++) {
                    yArray[x-startIndex] = viewAzimuthRaster.getSampleDouble(x, y);
               }

               final double minValue = GlintHelpers.getMinimumValueInDoubleArray(yArray);
               final double maxValue = GlintHelpers.getMinimumValueInDoubleArray(yArray);

               if (minValue != 0.0 || maxValue != 0.0) {
                   double[] correctedResult = getViewAzimuthCorrectionProfile(yArray);
                   for (int x=startIndex; x<endIndex; x++) {
                       viewAzimuthRaster.setSample(x, y, correctedResult[x-startIndex]);
                   }
               }
           }
        }
    }

    /**
     * This method provides a corrected view azmiuth profile
     * (currently with simple linear regression)
     *
     * @param yArray - the input profile
     * @return double - the corrected profile
     */
    protected static double[] getViewAzimuthCorrectionProfile(double[] yArray) {
        double[] result = new double[yArray.length];

        // get left side of discontinuity interpolation (kind of 'second derivative'...)
        final int discontLeftIndex = getDiscontinuityInterpolationLeftSide(yArray);
        // get right side of discontinuity interpolation
        final int discontRightIndex = getDiscontinuityInterpolationRightSide(yArray);

        final int discontIndex = (discontLeftIndex + discontRightIndex)/2;

        double[][] leftPart = new double[discontLeftIndex+1][2];
        double[][] rightPart = new double[yArray.length-discontRightIndex][2];

        final int leftPartLength = Math.min(discontLeftIndex, yArray.length - 1);
        for (int x=0; x<= leftPartLength; x++) {
            leftPart[x][0] =  x*1.0;
            leftPart[x][1] =  yArray[x];
        }
        for (int x=discontRightIndex; x<yArray.length; x++) {
            rightPart[x-discontRightIndex][0] = x*1.0;
            rightPart[x-discontRightIndex][1] = yArray[x];
        }

        if (leftPart[0].length < 2 || rightPart[0].length < 2) {
            // no regression possible
            return yArray;
        } else {
            double[] leftCoeffs = Regression.getOLSRegression(leftPart);
            for (int x=0; x<=leftPartLength; x++) {
                 result[x] = yArray[x];
            }
            for (int x=discontLeftIndex; x<=discontIndex; x++) {
                 result[x] = leftCoeffs[0] + leftCoeffs[1]*x;
            }

            double[] rightCoeffs = Regression.getOLSRegression(rightPart);
            for (int x=discontIndex+1; x<discontRightIndex; x++) {
                result[x] = rightCoeffs[0] + rightCoeffs[1]*x;
            }
            System.arraycopy(yArray, discontRightIndex, result, discontRightIndex,
                             yArray.length - discontRightIndex);

            return result;
        }
    }

    private static int getDiscontinuityInterpolationRightSide(double[] yArray) {
        int discontRightIndex = 0;
        for (int i=yArray.length-3; i>=2; i--) {
            double yArrayDiffQuot = (yArray[i+2] - yArray[i]) / (yArray[i] - yArray[i-2]);
            if (yArrayDiffQuot < 0.1 || yArrayDiffQuot > 10.0) {
                 discontRightIndex = i;
                 break;
            }
        }
        return discontRightIndex;
    }

    private static int getDiscontinuityInterpolationLeftSide(double[] yArray) {
        int discontLeftIndex = yArray.length;
        for (int i=2; i<yArray.length-2; i++) {
            final double yArrayDiffQuot = (yArray[i+2] - yArray[i]) / (yArray[i] - yArray[i-2]);
            if (yArrayDiffQuot < 0.1 || yArrayDiffQuot > 10.0) {
                discontLeftIndex = i;
                break;
            }
        }
        return discontLeftIndex;
    }

}
