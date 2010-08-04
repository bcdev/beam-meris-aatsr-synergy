package org.esa.beam.synergy.operators;

import org.esa.beam.synergy.util.GlintHelpers;
import org.esa.beam.util.math.MathUtils;

/**
 * Class providing methods for FUB Glint retrieval.
 *
 * @author Olaf Danne
 * @version $Revision: 8092 $ $Date: 2010-01-26 19:08:16 +0100 (Di, 26 Jan 2010) $
 */
public class GlintRetrieval {
    public static final double refractiveIndexReal037 = 1.37;
    public static final double refractiveIndexReal088 = 1.33;
    public static final double rhoFoam037 = 0.01;
    public static final double rhoFoam088 = 0.2;

    //
    // This method provides the final result (datapair [windspeed, MERIS normalized radiance])
    // after ambiguity reduction (ECMWF wind method)
    // (breadboard step 2.b.1)
    //
    protected float[] getAmbiguityReducedRadiance(float[][] merisNormalizedRadianceResult,
                                                  float zonalWind, float meridionalWind) {
        float[] result = new float[]{-1.0f, -1.0f};

        double windSpeed = Math.sqrt(zonalWind*zonalWind + meridionalWind*meridionalWind);

        final double wsDiff1 = Math.abs(merisNormalizedRadianceResult[0][0] - windSpeed);
        final double wsDiff2 = Math.abs(merisNormalizedRadianceResult[1][0] - windSpeed);

        if (wsDiff1 > wsDiff2 || merisNormalizedRadianceResult[0][0] == -1.0f) {
            result[0] = merisNormalizedRadianceResult[1][0];
            result[1] = merisNormalizedRadianceResult[1][1];
        } else {
            result[0] = merisNormalizedRadianceResult[0][0];
            result[1] = merisNormalizedRadianceResult[0][1];
        }
        return result;
    }

    //
    // This method indicates if a windspeed/radiance result was found from the LUT approach
    //
    protected int windspeedFound(float[][] radianceResult) {
        int numberWindspeedsFound = 0;

        if (radianceResult[0][0] != -1.0f || radianceResult[1][0] != -1.0f) {
            if (radianceResult[0][0] != -1.0f && radianceResult[1][0] != -1.0f) {
                numberWindspeedsFound = 2;
            }  else {
                numberWindspeedsFound = 1;
            }
        }
        return numberWindspeedsFound;
    }

    //
    // This method does all the steps of the geometrical conversion.
    // A 2x2 array of Meris of effective windspeeds and corresponding normalised radiances is returned:
    //      |ws0 rad0|
    //      |ws1 rad1|
    //
    //      - in the 'normal' case, the first element contains the required result
    //      - in the 'ambiguous' case, both elements are filled, and ambiguity must be removed
    //
    // (breadboard step 2.a)
    //
     protected float[][] convertAatsrRad37ToMerisRad(float[] aatsrRadInfo, float merisSunZenith, float merisViewZenith,
                                                float aatsrAzimuthDifference, float merisAzimuthDifference) {

        float[][] merisNormalizedRadianceResult = new float[][]{{-1.0f, -1.0f}, {-1.0f, -1.0f}};

        final double[][] normalizedRadianceLUT = createNormalizedRadianceLUT(merisSunZenith, merisViewZenith,
                aatsrAzimuthDifference);

        final double maximumAcceptableDiff = aatsrRadInfo[1];

        final int maximumNormalizedRadianceIndex = GlintHelpers.getMaximumValueIndexInDoubleArray(normalizedRadianceLUT[1]);

        if (maximumNormalizedRadianceIndex > 0 && maximumNormalizedRadianceIndex < normalizedRadianceLUT[1].length-1) {
            // two LUT solutions possible
            merisNormalizedRadianceResult[0] = getRadianceFromLUT(normalizedRadianceLUT, 0, maximumNormalizedRadianceIndex-1,
                    aatsrRadInfo[0], merisSunZenith, maximumAcceptableDiff, merisViewZenith, merisAzimuthDifference);

            merisNormalizedRadianceResult[1] = getRadianceFromLUT(normalizedRadianceLUT, maximumNormalizedRadianceIndex,
                    normalizedRadianceLUT[0].length-1,
                    aatsrRadInfo[0], merisSunZenith, maximumAcceptableDiff, merisViewZenith, merisAzimuthDifference);
        } else {
            // monotone
            final int lutLength = normalizedRadianceLUT[1].length;
            merisNormalizedRadianceResult[0] = getRadianceFromLUT(normalizedRadianceLUT, 0, lutLength-1,
                    aatsrRadInfo[0], merisSunZenith, maximumAcceptableDiff, merisViewZenith, aatsrAzimuthDifference);
        }

        return merisNormalizedRadianceResult;
    }

    private float[] getRadianceFromLUT(double[][] lut, int startIndex, int endIndex, float aatsrRad,
                                      float merisSunZenith, double maximumAcceptableDiff,
                                      float merisViewZenith, float merisAzimuthDifference) {

        float[] radianceResult = new float[]{-1.0f, -1.0f};

        final int lutLength = endIndex - startIndex + 1;
        double[] radianceDiffs = new double[lutLength];
        for (int i=startIndex; i<=endIndex; i++) {
            radianceDiffs[i-startIndex] = Math.abs(lut[1][i] - aatsrRad);
        }
        final int minRadianceDiffIndexInLUT = GlintHelpers.getMinimumValueIndexInDoubleArray(radianceDiffs);

        final double minRadianceDiffInLUT = GlintHelpers.getMinimumValueInDoubleArray(radianceDiffs);
        final double windspeed = lut[0][startIndex+minRadianceDiffIndexInLUT];

        if (minRadianceDiffInLUT <= maximumAcceptableDiff) {
            radianceResult[0] = (float) windspeed;
            radianceResult[1] = calcGlintAnalytical(merisSunZenith, merisViewZenith, merisAzimuthDifference, refractiveIndexReal088,
                                                    windspeed,  rhoFoam088);
        }


        return radianceResult;
    }

    //
    // This method generates a 1D LUT of AATSR normalized radiances for different wind speeds
    // (breadboard step 2.a.1)
    //
    private double[][] createNormalizedRadianceLUT(float merisSunZenith, float merisViewZenith,
                                                     float aatsrAzimuthDifference) {

        final int numberOfWindspeeds = 151;

        double[][] lookupTable = new double[2][numberOfWindspeeds];

        double[] windspeed = new double[numberOfWindspeeds];

        for (int i = 0; i < numberOfWindspeeds; i++) {
            windspeed[i] = i * 13.0 / (numberOfWindspeeds - 1) + 1.0;

            lookupTable[0][i] = windspeed[i];
            lookupTable[1][i] = calcGlintAnalytical(merisSunZenith, merisViewZenith, aatsrAzimuthDifference,
                                                     refractiveIndexReal037, windspeed[i], rhoFoam037);
        }

        return lookupTable;
    }

    public static float calcGlintAnalytical(float sunZenith, float viewZenith,
                                     float azimuthDifference, double refractiveIndex,
                                     double windspeed, double rhoFoam) {
        final double rhoGlint = calcGlintReflectionAnalytical(sunZenith, viewZenith, azimuthDifference, refractiveIndex, windspeed);
        final double foamPortion = 2.95 * 1.E-6 * Math.pow(windspeed, 3.25); // Koepke 1985
        final double rhoSurface = (1.0 - foamPortion) * rhoGlint + foamPortion * rhoFoam;
        final double sunZenithRad = MathUtils.DTOR * sunZenith;
        final double rhoSurfaceNormalized = rhoSurface / Math.PI * Math.cos(sunZenithRad);
        return (float) rhoSurfaceNormalized;
    }

    public static float calcGlintReflectionAnalytical(float sunZenith, float viewZenith,
                                               float azimuthDifference, double refractiveIndex,
                                               double windspeed) {
        final float azimuthDifferenceShifted = 180.0f - azimuthDifference;
        final double sunZenithRad = MathUtils.DTOR * sunZenith;
        final double viewZenithRad = MathUtils.DTOR * viewZenith;
        final double azimuthDifferenceShiftedRad = MathUtils.DTOR * azimuthDifferenceShifted;

        final double cos2refl = Math.cos(viewZenithRad) * Math.cos(sunZenithRad) +
                Math.sin(viewZenithRad) * Math.sin(sunZenithRad) * Math.cos(azimuthDifferenceShiftedRad);
        final double reflAngleRad = Math.acos(cos2refl) / 2.0;
        final double cosNorm = (Math.cos(viewZenithRad) + Math.cos(sunZenithRad)) / (2.0 * Math.cos(reflAngleRad));
        final double transAngleRad = Math.asin(Math.sin(reflAngleRad) / refractiveIndex);
        final double rho = 0.5 * Math.pow(Math.sin(reflAngleRad - transAngleRad) / Math.sin(reflAngleRad + transAngleRad), 2.0) +
                0.5 * Math.pow(Math.tan(reflAngleRad - transAngleRad) / Math.tan(reflAngleRad + transAngleRad), 2.0);
        final double normAngleRad = Math.acos(cosNorm);
        final double sig2 = 0.003 + 0.00512 * windspeed; // m/s
        final double prob = Math.exp(-Math.atan(normAngleRad) * Math.atan(normAngleRad) / sig2) / (Math.PI * sig2);
        final double brdf = Math.PI * rho * prob / (4.0 * Math.cos(viewZenithRad) * Math.cos(sunZenithRad) * Math.pow(cosNorm, 4.0));
        return (float) brdf;
    }

}
