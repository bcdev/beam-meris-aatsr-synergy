package org.esa.beam.synergy.util;

import java.awt.Color;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.synergy.util.math.Spline;
import org.esa.beam.util.Guardian;
import org.esa.beam.util.ProductUtils;

import javax.media.jai.Interpolation;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.ScaleDescriptor;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import org.esa.beam.framework.datamodel.BitmaskDef;
import org.esa.beam.framework.datamodel.RasterDataNode;

/**
 * Utility class for aerosol/SDR retrieval
 *
 * @author Olaf Danne
 * @version $Revision: 8041 $ $Date: 2010-01-20 16:23:15 +0000 (Mi, 20 Jan 2010) $
 */
public class AerosolHelpers {

    private static AerosolHelpers instance;

    public static AerosolHelpers getInstance() {
        if (instance == null) {
            instance = new AerosolHelpers();
        }

        return instance;
    }

    /**
     *
     * @param inputProduct
     * @param instr
     * @param bandList
     */
    public static void getGeometryBandList(Product inputProduct, String instr, ArrayList<RasterDataNode> bandList) {
        String[] viewArr = {"nadir", "fward"};
        int nView = viewArr.length;
        String[] bodyArr = {"sun", "view"};
        String[] angArr = {"elev", "azimuth"};
        String bandName;

        if (instr.equals("MERIS")) {
            angArr[0] = "zenith";
            nView = 1;
        }
        for (int iView = 0; iView < nView; iView++) {
            for (String body : bodyArr) {
                for(String ang : angArr) {
                    if (instr.equals("AATSR")) {
                        bandName = body + "_" + ang + "_" + viewArr[iView] + "_" +
                                SynergyConstants.INPUT_BANDS_SUFFIX_AATSR;
                        bandList.add(inputProduct.getBand(bandName));
                    } else {
                        bandName = body + "_" + ang;
                        bandList.add(inputProduct.getRasterDataNode(bandName));
                    }
                }
            }
        }
    }

    public static void getSpectralBandList(Product inputProduct, String bandNamePrefix, String bandNameSuffix,
            int[] excludeBandIndices, ArrayList<Band> bandList) {

        String[] bandNames = inputProduct.getBandNames();
        Comparator<Band> byWavelength = new WavelengthComparator();
        for (String name : bandNames) {
            if (name.startsWith(bandNamePrefix) && name.endsWith(bandNameSuffix)) {
                boolean exclude = false;
                if (excludeBandIndices != null) {
                    for (int i : excludeBandIndices) {
                        exclude = exclude || (i == inputProduct.getBand(name).getSpectralBandIndex() + 1);
                    }
                }
                if (!exclude) {
                    bandList.add(inputProduct.getBand(name));
                }
            }
        }
        Collections.sort(bandList, byWavelength);
    }

    /**
     * This method provides the min/max vector for ocean aerosol retrieval
     * * The method represents the breadboard IDL routine 'minmax'.
     *
     * @param inputArray - the input array
     * @param n - array dimension
     * @return float[]
     */
    public static float[] getMinMaxVector(float[] inputArray, int n) {
        float[] result = new float[n];

        float[] inputArrayCopy = new float[inputArray.length];
        System.arraycopy(inputArray, 0, inputArrayCopy, 0, inputArray.length);
        Arrays.sort(inputArrayCopy);

        float min = inputArrayCopy[0];
        float max = inputArrayCopy[inputArrayCopy.length - 1];

        for (int i = 0; i < n; i++) {
            result[i] = min + i * (max - min) / (n - 1);
        }

        return result;
    }

    /**
     * This method finds for each regular angstroem parameter
     * a PAIR of models which can be used for a weighted sum (interpolation).
     * The method represents the breadboard IDL routine
     * 'find_model_pairs_for_angstroem_interpolation'.
     *
     * @param angArray - array of Angstroem coefficients from aerosol model table
     * @param nAng     - number of Ang coeffs
     * @return AngstroemParameters[] - the model pairs
     */
    public AngstroemParameters[] getAngstroemParameters(float[] angArray, int nAng) {
        float[] minMaxVector = AerosolHelpers.getMinMaxVector(angArray, nAng);
        AngstroemParameters[] angstroemParameters = new AngstroemParameters[minMaxVector.length];

        for (int i = 0; i < minMaxVector.length; i++) {
            int lowerIndex = AerosolHelpers.getNearestLowerValueIndexInFloatArray(minMaxVector[i], angArray);
            angstroemParameters[i] = new AngstroemParameters();
            angstroemParameters[i].setIndexPairs(0, lowerIndex);
            int higherIndex = AerosolHelpers.getNearestHigherValueIndexInFloatArray(minMaxVector[i], angArray);
            angstroemParameters[i].setIndexPairs(1, higherIndex);
            angstroemParameters[i].setValue(minMaxVector[i]);
        }

        for (int i = 0; i < minMaxVector.length; i++) {
            float lowerAng = angArray[angstroemParameters[i].getIndexPairs()[0]];
            float higherAng = angArray[angstroemParameters[i].getIndexPairs()[1]];

            float distance = lowerAng - higherAng;
            if (Math.abs(distance) > 0.01) {
                double wgt0 = 1.0 - (lowerAng - minMaxVector[i]) / distance;
                angstroemParameters[i].setWeightPairs(0, wgt0);
                double wgt1 = 1.0 - (minMaxVector[i] - higherAng) / distance;
                angstroemParameters[i].setWeightPairs(1, wgt1);
            } else {
                angstroemParameters[i].setWeightPairs(0, 1.0);
                angstroemParameters[i].setWeightPairs(1, 0.0);
            }
        }

        return angstroemParameters;
    }

    /**
     * This method computed the index of the nearest higher value in a float array
     * compared to a given input float value
     *
     * @param x - input value
     * @param array - the float array
     * @return int
     */
    public static int getNearestHigherValueIndexInFloatArray(float x, float[] array) {
        int nearestValueIndex = -1;
        float big = Float.MAX_VALUE;

        for (int i = 0; i < array.length; i++) {
            if (x <= array[i]) {
                if (array[i] - x < big) {
                    big = array[i] - x;
                    nearestValueIndex = i;
                }
            }
        }
        if (nearestValueIndex == -1) {
            throw new OperatorException("Failed to create Angstroem model pairs!\n");
        }

        return nearestValueIndex;
    }

    /**
     * This method computed the index of the nearest lower value in a float array
     * compared to a given input float value
     *
     * @param x - input value
     * @param array - the float array
     * @return int
     */
    public static int getNearestLowerValueIndexInFloatArray(float x, float[] array) {
        int nearestValueIndex = -1;
        float big = Float.MAX_VALUE;

        for (int i = 0; i < array.length; i++) {
            if (x >= array[i]) {
                if (x - array[i] < big) {
                    big = x - array[i];
                    nearestValueIndex = i;
                }
            }
        }

        if (nearestValueIndex == -1) {
            throw new OperatorException("Failed to create Angstroem model pairs!\n");
        }

        return nearestValueIndex;
    }

    /**
     * This method spline-interpolates within a double array to upscale
     * the array to a given dimension
     *
     * @param yIn - input array
     * @param dimOut - dimension of output array
     * @return  double[] - the upscaled array
     */
    public static double[] interpolateArray(double[] yIn, int dimOut) {
        double[] yOut = new double[dimOut];

        final int numIntervals = yIn.length - 1;
        final int numPointsPerInterval = dimOut / numIntervals;
        Spline splineInterpol = new Spline(yIn);
        int outIndex = 0;
        // interpolate in all intervals:
        for (int i = 0; i < numIntervals; i++) {
            yOut[outIndex++] = yIn[i];
            for (int j = 1; j < numPointsPerInterval; j++) {
                final double fraction = ((double) j) / numPointsPerInterval;
                yOut[outIndex++] = splineInterpol.fn(i, fraction);
            }
        }
        // don't forget the last points if there are some left:
        for (int i = outIndex; i < yOut.length; i++) {
            yOut[i] = yIn[yIn.length - 1];
        }

        return yOut;
    }

    /**
     * This method provides an average value of nxn pixels around center pixel
     *
     * @param inputProduct - the input product
     * @param inputTile    - tile containing the pixels
     * @param aveBlockSize - half size of nxn square
     * @param minAverages  - number of 'good' values required for average computation
     * @param iTarX - x value
     * @param iTarY - y value
     * @return float
     */
    public static float getAvePixelFloat(Product inputProduct, Tile inputTile,
                                   int aveBlockSize, int minAverages,
                                   int iTarX, int iTarY) {

        double value = 0;
        double noDataValue = 0;
        int n = 0;

        final int minX = Math.max(0, iTarX - aveBlockSize);
        final int minY = Math.max(0, iTarY - aveBlockSize);
        final int maxX = Math.min(inputProduct.getSceneRasterWidth() - 1, iTarX + aveBlockSize);
        final int maxY = Math.min(inputProduct.getSceneRasterHeight() - 1, iTarY + aveBlockSize);

        for (int iy = minY; iy <= maxY; iy++) {
            for (int ix = minX; ix <= maxX; ix++) {
                double val = inputTile.getSampleDouble(ix, iy);
                noDataValue = inputTile.getRasterDataNode().getNoDataValue();
                boolean valid = (Double.compare(val, noDataValue) != 0);
                if (valid) {
                    n++;
                    value += val;
                }
            }
        }
        if (!(n < minAverages)) {
            value /= n;
        } else {
            value = noDataValue;
        }

        return (float) value;
    }

    /**
     * This method copies the flag bands from the synergy product to the target product
     *
     * @param synergyProduct - the Synergy product
     * @param targetProduct - the target product
     */
    public static void copySynergyFlagBands(Product synergyProduct, Product targetProduct) {
        Band aatsrConfidFlagNadirBand = targetProduct.addBand(SynergyConstants.CONFID_NADIR_FLAGS_AATSR, ProductData.TYPE_INT16);
        Band aatsrConfidFlagFwardBand = targetProduct.addBand(SynergyConstants.CONFID_FWARD_FLAGS_AATSR, ProductData.TYPE_INT16);
        Band aatsrCloudFlagNadirBand = targetProduct.addBand(SynergyConstants.CLOUD_NADIR_FLAGS_AATSR, ProductData.TYPE_INT16);
        Band aatsrCloudFlagFwardBand = targetProduct.addBand(SynergyConstants.CLOUD_FWARD_FLAGS_AATSR, ProductData.TYPE_INT16);
        Band merisL1FlagsBand = targetProduct.addBand(SynergyConstants.L1_FLAGS_MERIS, ProductData.TYPE_INT16);
        Band merisCloudFlagBand = targetProduct.addBand(SynergyConstants.CLOUD_FLAG_MERIS, ProductData.TYPE_INT16);

        FlagCoding aatsrConfidNadirFlagCoding = synergyProduct.getFlagCodingGroup().get(SynergyConstants.CONFID_NADIR_FLAGS_AATSR);
        ProductUtils.copyFlagCoding(aatsrConfidNadirFlagCoding, targetProduct);
        aatsrConfidFlagNadirBand.setSampleCoding(aatsrConfidNadirFlagCoding);

        FlagCoding aatsrConfidFwardFlagCoding = synergyProduct.getFlagCodingGroup().get(SynergyConstants.CONFID_FWARD_FLAGS_AATSR);
        ProductUtils.copyFlagCoding(aatsrConfidFwardFlagCoding, targetProduct);
        aatsrConfidFlagFwardBand.setSampleCoding(aatsrConfidFwardFlagCoding);

        FlagCoding aatsrCloudNadirFlagCoding = synergyProduct.getFlagCodingGroup().get(SynergyConstants.CLOUD_NADIR_FLAGS_AATSR);
        ProductUtils.copyFlagCoding(aatsrCloudNadirFlagCoding, targetProduct);
        aatsrCloudFlagNadirBand.setSampleCoding(aatsrCloudNadirFlagCoding);

        FlagCoding aatsrCloudFwardFlagCoding = synergyProduct.getFlagCodingGroup().get(SynergyConstants.CLOUD_FWARD_FLAGS_AATSR);
        ProductUtils.copyFlagCoding(aatsrCloudFwardFlagCoding, targetProduct);
        aatsrCloudFlagFwardBand.setSampleCoding(aatsrCloudFwardFlagCoding);

        FlagCoding merisL1FlagsCoding = synergyProduct.getFlagCodingGroup().get(SynergyConstants.L1_FLAGS_MERIS);
        ProductUtils.copyFlagCoding(merisL1FlagsCoding, targetProduct);
        merisL1FlagsBand.setSampleCoding(merisL1FlagsCoding);

        FlagCoding merisCloudFlagCoding = synergyProduct.getFlagCodingGroup().get(SynergyConstants.CLOUD_FLAG_MERIS);
        ProductUtils.copyFlagCoding(merisCloudFlagCoding, targetProduct);
        merisCloudFlagBand.setSampleCoding(merisCloudFlagCoding);
    }

    /**
     * This method copies selected tie point grids to a downscaled target product
     *
     * @param sourceProduct - the source product
     * @param targetProduct - the target product
     * @param scalingFactor - factor of downscaling
     */
    public static void copyDownscaledTiePointGrids(Product sourceProduct, Product targetProduct, float scalingFactor) {
        // Add tie point grids for sun/view zenith/azimuths. Get data from AATSR bands.
        Band szaBand = sourceProduct.getBand("sun_elev_nadir_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band saaBand = sourceProduct.getBand("sun_azimuth_nadir_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band latitudeBand = sourceProduct.getBand("latitude_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band longitudeBand = sourceProduct.getBand("longitude_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band altitudeBand = sourceProduct.getBand("altitude_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);

        Band szaDownscaledBand = downscaleBand(szaBand, scalingFactor);
        Band saaDownscaledBand = downscaleBand(saaBand, scalingFactor);
        Band latitudeDownscaledBand = downscaleBand(latitudeBand, scalingFactor);
        Band longitudeDownscaledBand = downscaleBand(longitudeBand, scalingFactor);
        Band altitudeDownscaledBand = downscaleBand(altitudeBand, scalingFactor);

        addTpg(targetProduct, szaDownscaledBand, "sun_zenith");
        addTpg(targetProduct, saaDownscaledBand, "sun_azimuth");
        // unscaled latitude/longitude TPGs were added by 'copyGeoCoding', we have to remove them before downscaling
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("latitude"));
        targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("longitude"));
        addTpg(targetProduct, latitudeDownscaledBand, "latitude");
        addTpg(targetProduct, longitudeDownscaledBand, "longitude");
        addTpg(targetProduct, altitudeDownscaledBand, "altitude");
    }

     /**
     * This method copies selected tie point grids to a rescaled target product
     *
     * @param sourceProduct - the source product
     * @param targetProduct - the target product
     * @param xScalingFactor - scaling factor in x-direction
     * @param yScalingFactor - scaling factor in y-direction
     */
    public static void copyRescaledTiePointGrids(Product sourceProduct, Product targetProduct,
                                                 int xScalingFactor, int yScalingFactor) {
        // Add tie point grids for sun/view zenith/azimuths. Get data from AATSR bands.
        Band szaBand = sourceProduct.getBand("sun_elev_nadir_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band saaBand = sourceProduct.getBand("sun_azimuth_nadir_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band latitudeBand = sourceProduct.getBand("latitude_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band longitudeBand = sourceProduct.getBand("longitude_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);
        Band altitudeBand = sourceProduct.getBand("altitude_" + SynergyConstants.INPUT_BANDS_SUFFIX_AATSR);

        TiePointGrid szaTpg = getRescaledTpgFromBand(szaBand, xScalingFactor, yScalingFactor);
        targetProduct.addTiePointGrid(szaTpg);
        TiePointGrid saaTpg = getRescaledTpgFromBand(saaBand, xScalingFactor, yScalingFactor);
        targetProduct.addTiePointGrid(saaTpg);
        TiePointGrid latTpg = getRescaledTpgFromBand(latitudeBand, xScalingFactor, yScalingFactor);
        targetProduct.addTiePointGrid(latTpg);
        TiePointGrid lonTpg = getRescaledTpgFromBand(longitudeBand, xScalingFactor, yScalingFactor);
        targetProduct.addTiePointGrid(lonTpg);
        TiePointGrid altTpg = getRescaledTpgFromBand(altitudeBand, xScalingFactor, yScalingFactor);
        targetProduct.addTiePointGrid(altTpg);

    }

    private static void addTpg(Product targetProduct, Band scaledBand, String name) {
        DataBuffer dataBuffer;
        float[] tpgData;
        TiePointGrid tpg;
        dataBuffer = scaledBand.getSourceImage().getData().getDataBuffer();
        tpgData = new float[dataBuffer.getSize()];
        for (int i = 0; i < dataBuffer.getSize(); i++) {
            tpgData[i] = dataBuffer.getElemFloat(i);
        }
        tpg = new TiePointGrid(name,
                               scaledBand.getSceneRasterWidth(),
                               scaledBand.getSceneRasterHeight(),
                               0.0f, 0.0f, 1.0f, 1.0f, tpgData);
        targetProduct.addTiePointGrid(tpg);
    }

    /**
     * This method provides a real tie point grid from a 'tie point band'.
     *
     * @param band - the 'tie point band'
     * @return TiePointGrid
     */
    public static TiePointGrid getTpgFromBand(Band band) {
        DataBuffer dataBuffer = band.getSourceImage().getData().getDataBuffer();
        float[] tpgData = new float[dataBuffer.getSize()];
        for (int i = 0; i < dataBuffer.getSize(); i++) {
            tpgData[i] = dataBuffer.getElemFloat(i);
        }

        TiePointGrid tpg = new TiePointGrid(band.getName(),
                                            band.getSceneRasterWidth(),
                                            band.getSceneRasterHeight(),
                                            0.0f, 0.0f, 1.0f, 1.0f, tpgData);
        return tpg;
    }

    /**
     * This method provides a rescaled tie point grid from a 'tie point band'.
     *
     * @param band - the 'tie point band'
     * @param rescaledWidth - width of the rescaled TPG
     * @param rescaledHeight - height of the rescaled TPG
     * @return TiePointGrid
     */
    public static TiePointGrid getRescaledTpgFromBand(Band band, int rescaledWidth, int rescaledHeight) {
        DataBuffer dataBuffer = band.getSourceImage().getData().getDataBuffer();
        float[] tpgData = new float[rescaledWidth * rescaledHeight];
        if (rescaledWidth * rescaledHeight > band.getSceneRasterWidth() * band.getSceneRasterHeight()) {
            throw new OperatorException("Cannot create TPG - width*height too large.");
        }
        int tpgIndex = 0;
        for (int j = 0; j < rescaledHeight; j++) {
            for (int i = 0; i < rescaledWidth; i++) {
                tpgData[rescaledWidth * j + i] = dataBuffer.getElemFloat(tpgIndex);
                tpgIndex++;
            }
            for (int i = rescaledWidth; i < band.getSceneRasterWidth(); i++) {
                tpgIndex++;
            }
        }
        TiePointGrid tpg = new TiePointGrid(band.getName(),
                                            rescaledWidth,
                                            rescaledHeight,
                                            0.0f, 0.0f, 1.0f, 1.0f, tpgData);
        return tpg;
    }

    /**
     * This method downscales a band by a given factor
     *
     * @param inputBand  - the input band
     * @param scalingFactor - the scaling factor
     * @return Band - the downscaled band
     */
    public static Band downscaleBand(Band inputBand, float scalingFactor) {
        RenderedImage sourceImage = inputBand.getSourceImage();
//        System.out.printf("Source, size: %d x %d\n", sourceImage.getWidth(), sourceImage.getHeight());
//        System.out.printf("Scaling factor: %f\n", scalingFactor);
        RenderedOp downscaledImage = ScaleDescriptor.create(sourceImage,
                                                            1.0f / scalingFactor,
                                                            1.0f / scalingFactor,
                                                            0.0f, 0.0f,
                                                            Interpolation.getInstance(Interpolation.INTERP_NEAREST),
                                                            null);
//        System.out.printf("Downscaled, size: %d x %d\n", downscaledImage.getWidth(), downscaledImage.getHeight());
        Band downscaledBand = new Band(inputBand.getName(), inputBand.getDataType(),
                                       downscaledImage.getWidth(), downscaledImage.getHeight());

        downscaledBand.setSourceImage(downscaledImage);
        return downscaledBand;
    }

    /**
     * This method copies all bands which contain a flagcoding from the source product
     * to the target product.
     *
     * @param sourceProduct the source product
     * @param targetProduct the target product
     */
    public static void copyDownscaledFlagBands(Product sourceProduct, Product targetProduct, float scalingFactor) {
        Guardian.assertNotNull("source", sourceProduct);
        Guardian.assertNotNull("target", targetProduct);
//        if (sourceProduct.getNumFlagCodings() > 0) {
        if (sourceProduct.getFlagCodingGroup().getNodeCount() > 0) {
            Band sourceBand;
            Band targetBand;
            FlagCoding coding;

            ProductUtils.copyFlagCodings(sourceProduct, targetProduct);
            ProductUtils.copyBitmaskDefs(sourceProduct, targetProduct);

            // loop over bands and check if they have a flags coding attached
            for (int i = 0; i < sourceProduct.getNumBands(); i++) {
                sourceBand = sourceProduct.getBandAt(i);
                coding = sourceBand.getFlagCoding();
                if (coding != null) {
                    targetBand = AerosolHelpers.downscaleBand(sourceBand, scalingFactor);
                    targetBand.setSampleCoding(coding);
                    targetProduct.addBand(targetBand);
                }
            }
        }
    }

    public static void addAerosolFlagBand(Product targetProduct, int rasterWidth, int rasterHeight) {
        FlagCoding aerosolFlagCoding = new FlagCoding(SynergyConstants.aerosolFlagCodingName);
        aerosolFlagCoding.addFlag(SynergyConstants.flagCloudyName, SynergyConstants.cloudyMask, SynergyConstants.flagCloudyDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagOceanName,  SynergyConstants.oceanMask,   SynergyConstants.flagOceanDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagSuccessName, SynergyConstants.successMask, SynergyConstants.flagSuccessDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagBorderName, SynergyConstants.borderMask, SynergyConstants.flagBorderDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagFilledName, SynergyConstants.filledMask, SynergyConstants.flagFilledDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagNegMetricName, SynergyConstants.negMetricMask, SynergyConstants.flagNegMetricDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagAotLowName, SynergyConstants.aotLowMask, SynergyConstants.flagAotLowDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagErrHighName, SynergyConstants.errHighMask, SynergyConstants.flagErrHighDesc);
        aerosolFlagCoding.addFlag(SynergyConstants.flagCoastName, SynergyConstants.coastMask, SynergyConstants.flagCoastDesc);
        targetProduct.getFlagCodingGroup().add(aerosolFlagCoding);

        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagCloudyName,
                                SynergyConstants.flagCloudyDesc,
                                SynergyConstants.aerosolFlagCodingName+"."+ SynergyConstants.flagCloudyName,
                                Color.lightGray, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagOceanName,
                                SynergyConstants.flagOceanDesc,
                                SynergyConstants.aerosolFlagCodingName+"."+ SynergyConstants.flagOceanName,
                                Color.blue, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagSuccessName,
                                SynergyConstants.flagSuccessDesc,
                                SynergyConstants.aerosolFlagCodingName+"."+ SynergyConstants.flagSuccessName,
                                Color.pink, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagBorderName,
                                SynergyConstants.flagBorderDesc,
                                SynergyConstants.aerosolFlagCodingName+"."+ SynergyConstants.flagBorderName,
                                Color.orange, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagFilledName,
                                SynergyConstants.flagFilledDesc,
                                SynergyConstants.aerosolFlagCodingName + "." + SynergyConstants.flagFilledName,
                                Color.magenta, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagNegMetricName,
                                SynergyConstants.flagNegMetricDesc,
                                SynergyConstants.aerosolFlagCodingName + "." + SynergyConstants.flagNegMetricName,
                                Color.magenta, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagAotLowName,
                                SynergyConstants.flagAotLowDesc,
                                SynergyConstants.aerosolFlagCodingName + "." + SynergyConstants.flagAotLowName,
                                Color.magenta, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagErrHighName,
                                SynergyConstants.flagErrHighDesc,
                                SynergyConstants.aerosolFlagCodingName + "." + SynergyConstants.flagErrHighName,
                                Color.magenta, 0.2f));
        targetProduct.addBitmaskDef(
                new BitmaskDef( SynergyConstants.flagCoastName,
                                SynergyConstants.flagCoastDesc,
                                SynergyConstants.aerosolFlagCodingName + "." + SynergyConstants.flagCoastName,
                                Color.magenta, 0.2f));

        Band targetBand = new Band(SynergyConstants.aerosolFlagCodingName, ProductData.TYPE_UINT16, rasterWidth, rasterHeight);
        targetBand.setDescription(SynergyConstants.aerosolFlagCodingDesc);
        targetBand.setSampleCoding(aerosolFlagCoding);
        targetProduct.addBand(targetBand);
    }

    // currently not used
//    public static void upscaleIntegerPixels(Tile sourceTile, Tile targetTile, int scalingFactor, int xMax, int yMax) {
//        Rectangle targetRectangle = targetTile.getRectangle();
//        Rectangle sourceRectangle = new Rectangle((targetRectangle.x / scalingFactor),
//                                                  (targetRectangle.y / scalingFactor),
//                                                  (targetRectangle.width / scalingFactor),
//                                                  (targetRectangle.height / scalingFactor));
//        int numAve = scalingFactor + 1;
//        for (int y = targetRectangle.y; y < Math.min(yMax, targetRectangle.y + targetRectangle.height); y++) {
//            for (int x = targetRectangle.x; x < Math.min(xMax, targetRectangle.x + targetRectangle.width); x++) {
//                int xAer = x / (scalingFactor + 1);
//                int yAer = y / (scalingFactor + 1);
//                final int sourceValue = sourceTile.getSampleInt(xAer, yAer);
//                targetTile.setSample(x, y, sourceValue);
//            }
//        }
//    }

    /**
     * Class representing a set of Angstroem parameters
     * (as specified in IDL breadboard)
     */
    public class AngstroemParameters {
        private int[] indexPairs;
        private double[] weightPairs;
        float value;

        public AngstroemParameters() {
            indexPairs = new int[2];
            weightPairs = new double[2];
        }

        public int[] getIndexPairs() {
            return indexPairs;
        }

        public double[] getWeightPairs() {
            return weightPairs;
        }

        public double getValue() {
            return value;
        }

        public void setIndexPairs(int index, int value) {
            indexPairs[index] = value;
        }

        public void setWeightPairs(int index, double value) {
            weightPairs[index] = value;
        }

        public void setValue(float value) {
            this.value = value;
        }
    }
}
