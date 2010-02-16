/* 
 * Copyright (C) 2002-2008 by Brockmann Consult
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.synergy.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.logging.BeamLogManager;

//import javax.swing.JOptionPane;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Operator for recalibrating AATSR reflectances within Synergy project.
 * todo: remove from Synergy project, provide as separate BEAM plugin!
 *
 * @author Olaf Danne
 * @version $Revision: 7612 $ $Date: 2009-12-18 19:21:00 +0100 (Fr, 18 Dez 2009) $
 */
@OperatorMetadata(alias = "synergy.RecalibrateAATSRReflectances",
                  version = "1.0-SNAPSHOT",
                  authors = "Ralf Quast, Olaf Danne",
                  copyright = "(c) 2008 by Brockmann Consult",
                  description = "Recalibrate AATSR Reflectances.")
public class RecalibrateAATSRReflectancesOp extends Operator {
    @SourceProduct(alias = "source",
            label = "Name (AATSR L1b product)",
                   description = "Select an AATSR L1b product.")
    private Product sourceProduct;
    @TargetProduct(description = "The target product. Contains the recalibrated reflectances.")
    private Product targetProduct;

    @Parameter(defaultValue = "false",
               label = "Use own drift corrections table")
    boolean useOwnDriftTable;

    @Parameter(alias = "DRIFT_TABLE_FILE_PATH",
               defaultValue = "",
               description = "User-specific AATSR drift corrections table file (leave blank to use default)",
               label = "Drift corrections table file")
    private File userDriftTablePath;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Nadir 1600")
    boolean recalibrateNadir1600;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Nadir 870")
    boolean recalibrateNadir0870;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Nadir 670")
    boolean recalibrateNadir0670;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Nadir 550")
    boolean recalibrateNadir0550;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Forward 1600")
    boolean recalibrateFward1600;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Forward 870")
    boolean recalibrateFward0870;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Forward 670")
    boolean recalibrateFward0670;

    @Parameter(defaultValue = "true",
               label = "Recalibrate Forward 550")
    boolean recalibrateFward0550;

    //    private static final String INVALID_EXPRESSION = "l1_flags.INVALID or l1_flags.LAND_OCEAN";
    @SuppressWarnings("unused")
	private static final String INVALID_EXPRESSION = "";  // TBD

    public static final String CONFID_NADIR_FLAGS = "confid_flags_nadir";
    public static final String CONFID_FWARD_FLAGS = "confid_flags_fward";
    public static final String CLOUD_NADIR_FLAGS = "cloud_flags_nadir";
    public static final String CLOUD_FWARD_FLAGS = "cloud_flags_fward";

    private static final String DRIFT_TABLE_FILE_NAME = "AATSR_VIS_DRIFT_V00-7.DAT";
    // make sure that the following value corresponds to the file above 
    private static final int DRIFT_TABLE_MAX_LENGTH = 5000;
    private static final int DRIFT_TABLE_HEADER_LINES = 6;

    // Gregorian Calendar adopted Oct. 15, 1582 (2299161)
    public static final int JGREG = 15 + 31 * (10 + 12 * 1582);
    private static final double SECONDS_PER_DAY = 86400;

    private static final int CHANNEL550 = 0;
    private static final int CHANNEL670 = 1;
    private static final int CHANNEL870 = 2;
    private static final int CHANNEL1600 = 3;

    private static final Map<String, String> months = new HashMap<String, String>();

    static {
        months.put("JAN", "1");
        months.put("FEB", "2");
        months.put("MAR", "3");
        months.put("APR", "4");
        months.put("MAY", "5");
        months.put("JUN", "6");
        months.put("JUL", "7");
        months.put("AUG", "8");
        months.put("SEP", "9");
        months.put("OCT", "10");
        months.put("NOV", "11");
        months.put("DEC", "12");
    }

    private DriftTable driftTable;
    private int driftTableLength;

    private String envisatLaunch = "01-MAR-2002 00:00:00";
    private String sensingStart;

    private Logger logger;
    private boolean doRecalibration;

    @Override
    public void initialize() throws OperatorException {
        logger = BeamLogManager.getSystemLogger();
        try {
            readDriftTable();
        } catch (Exception e) {
            throw new OperatorException("Failed to load aux data:\n" + e.getMessage());
        }

        if (sourceProduct != null) {
            // todo: check if a preferred tile size should be set...
            // sourceProduct.setPreferredTileSize(16, 16);
            createTargetProduct();
        }
        sensingStart = sourceProduct.getMetadataRoot()
                .getElement("MPH").getAttribute("SENSING_START").getData().getElemString().substring(0, 20);
        doRecalibration = checkAcquisitionTimeRange(sensingStart);

        if (!doRecalibration) {
            //do not put in operator!!!
//            final int answer = JOptionPane.showConfirmDialog(null, "date out of range",
//                                                                 "Test", JOptionPane.YES_NO_OPTION);
        }
    }

    /**
     * This method creates the target product
     */
    private void createTargetProduct() {
        String productType = sourceProduct.getProductType();
        String productName = sourceProduct.getName();
        int sceneWidth = sourceProduct.getSceneRasterWidth();
        int sceneHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(productName, productType, sceneWidth, sceneHeight);

        // loop over bands and create them
        for (Band band : sourceProduct.getBands()) {
            if (!band.isFlagBand())
                ProductUtils.copyBand(band.getName(), sourceProduct, targetProduct);
        }
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        setFlagBands();

//        BandArithmeticOp bandArithmeticOp =
//            BandArithmeticOp.createBooleanExpressionBand(INVALID_EXPRESSION, sourceProduct);
//        Band invalidBand = bandArithmeticOp.getTargetProduct().getBandAt(0);
    }

    /**
     * This method sets up the flag bands for the target product
     */
    private void setFlagBands() {
        Band confidFlagNadirBand = targetProduct.addBand(CONFID_NADIR_FLAGS, ProductData.TYPE_INT16);
        Band confidFlagFwardBand = targetProduct.addBand(CONFID_FWARD_FLAGS, ProductData.TYPE_INT16);
        Band cloudFlagNadirBand = targetProduct.addBand(CLOUD_NADIR_FLAGS, ProductData.TYPE_INT16);
        Band cloudFlagFwardBand = targetProduct.addBand(CLOUD_FWARD_FLAGS, ProductData.TYPE_INT16);

        FlagCoding confidNadirFlagCoding = sourceProduct.getFlagCodingGroup().get(CONFID_NADIR_FLAGS);
        ProductUtils.copyFlagCoding(confidNadirFlagCoding, targetProduct);
        confidFlagNadirBand.setSampleCoding(confidNadirFlagCoding);

        FlagCoding confidFwardFlagCoding = sourceProduct.getFlagCodingGroup().get(CONFID_FWARD_FLAGS);
        ProductUtils.copyFlagCoding(confidFwardFlagCoding, targetProduct);
        confidFlagFwardBand.setSampleCoding(confidFwardFlagCoding);

        FlagCoding cloudNadirFlagCoding = sourceProduct.getFlagCodingGroup().get(CLOUD_NADIR_FLAGS);
        ProductUtils.copyFlagCoding(cloudNadirFlagCoding, targetProduct);
        cloudFlagNadirBand.setSampleCoding(cloudNadirFlagCoding);

        FlagCoding cloudFwardFlagCoding = sourceProduct.getFlagCodingGroup().get(CLOUD_FWARD_FLAGS);
        ProductUtils.copyFlagCoding(cloudFwardFlagCoding, targetProduct);
        cloudFlagFwardBand.setSampleCoding(cloudFwardFlagCoding);
    }


    @Override
    public void dispose() {
    }

    private void readDriftTable() throws IOException {
        BufferedReader bufferedReader = null;
        InputStream inputStream = null;
        if (!useOwnDriftTable || userDriftTablePath == null || userDriftTablePath.length() == 0) {
            inputStream = RecalibrateAATSRReflectancesOp.class.getResourceAsStream(DRIFT_TABLE_FILE_NAME);
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        } else {
            if (userDriftTablePath.isFile()) {
                bufferedReader = new BufferedReader(new FileReader(userDriftTablePath));
            } else {
                throw new OperatorException("Failed to load drift correction table '" + userDriftTablePath + "'.");
            }
        }

        StringTokenizer st;
        try {
            driftTable = new DriftTable();

            // skip header lines
            for (int i = 0; i < DRIFT_TABLE_HEADER_LINES; i++) {
                bufferedReader.readLine();
            }

            int i = 0;
            String line;
            while ((line = bufferedReader.readLine()) != null && i < DRIFT_TABLE_MAX_LENGTH) {
                line = line.substring(8); // skip index column
                line = line.trim();
                st = new StringTokenizer(line, "   ", false);
                if (st.hasMoreTokens()) {
                    // date and time (2 tokens)
                    String date = st.nextToken() + " " + st.nextToken();
                    driftTable.setDate(i, date);
                }
                if (st.hasMoreTokens()) {
                    // drift560
                    driftTable.setDrift560(i, Double.parseDouble(st.nextToken()));
                }
                if (st.hasMoreTokens()) {
                    // drift660
                    driftTable.setDrift670(i, Double.parseDouble(st.nextToken()));
                }
                if (st.hasMoreTokens()) {
                    // drift870
                    driftTable.setDrift870(i, Double.parseDouble(st.nextToken()));
                }
                if (st.hasMoreTokens()) {
                    // drift1600
                    driftTable.setDrift1600(i, Double.parseDouble(st.nextToken()));
                }
                i++;
            }
            driftTableLength = i;
        } catch (IOException e) {
            throw new OperatorException("Failed to load Drift Correction Table: \n" + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new OperatorException("Failed to load Drift Correction Table: \n" + e.getMessage(), e);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    /**
     * This method performs the nonlinearity correction for the 1.6um channel
     *
     * @param gc1Filename - the GC1 file name (given in DSD.32 metadata)
     * @param reflectance - input reflectance
     *
     * @return correctedReflectance
     */
    private double getV16NonlinearityCorrectedReflectance(String gc1Filename, double reflectance) {
        double correctedReflectance;
        double volts;

        // Nonlinearity coefficients from pre-launch calibration
        final double[] A = new double[]{-0.000027, -0.1093, 0.009393, 0.001013};

        // Find out if nonlinearity correction has already been applied - uses name of GC1 file

        // Nonlinearity Correction NOT yet applied:
        if (gc1Filename.equals("ATS_GC1_AXVIEC20020123_073430_20020101_000000_20200101_000000")) {
            // Convert 1.6um reflectance back to raw signal using linear conversion
            volts = -0.816 * (reflectance/100.0) / 0.192;
            // Convert 1.6um raw signal to reflectance using non-linear conversion function
            correctedReflectance = Math.PI * (A[0] + A[1]*volts + A[2]*volts*volts + A[3]*volts*volts*volts) / 1.553;
            correctedReflectance *= 100.0;
        } else {
            correctedReflectance = reflectance;
        }
        return correctedReflectance;
    }
    
    /**
     * This method computes the drift correction which has to be removed according to the
     * correction index as determined in {@link #getRemoveDriftCorrectionIndex}
     *
     * @param iChannel    - the input channel index
     * @param correction  - the correction index
     * @param tDiff       - time difference between sensing start and Envisat launch time
     * @param reflectance - input reflectance
     *
     * @return uncorrectedReflectance
     */
    private static double removeDriftCorrection(int iChannel, int correction, double tDiff, double reflectance) {
        double uncorrectedReflectance;
        double drift = 1.0;

        // yearly drift rates for exponential drift
        final double[] K = new double[]{0.034, 0.021, 0.013, 0.002};

        // Thin film drift model coefficients
        final double[][] A = new double[][]{{0.083, 1.5868E-3},
                {0.056, 1.2374E-3},
                {0.041, 9.6111E-4}};


        if ((iChannel == CHANNEL1600 && correction != 0) ||
                (iChannel != CHANNEL1600 && correction == 1)) {
            drift = Math.exp(K[iChannel] * tDiff / 365.0);
        }

        if (iChannel != CHANNEL1600 && correction == 2) {
            final double s = Math.sin(A[iChannel][1] * tDiff);
            drift = 1.0 + A[iChannel][0] * s * s;
        }

        uncorrectedReflectance = reflectance * drift;

        return uncorrectedReflectance;
    }

    /**
     * This method determines which drift correction had been applied on reflectances.
     * For this, the name of the VC1 file is used.
     *
     * @param vc1Filename - the VC1 file name (given in DSD.31 metadata)
     *
     * @return correctionIndex
     */
    private int getRemoveDriftCorrectionIndex(String vc1Filename) {
        String year = vc1Filename.substring(14, 18);
        String month = vc1Filename.substring(18, 20);
        String day = vc1Filename.substring(20, 22);
        String hour = vc1Filename.substring(23, 25);
        String mins = vc1Filename.substring(25, 27);
        String secs = vc1Filename.substring(27, 29);

        String refTime = day + '-' + month + '-' + year + ' ' + hour + ':' + mins + ':' + secs;

        int correctionIndex;
        // Now Identify Which Correction Has Been Applied
        if (getTimeInMillis(refTime) < getTimeInMillis("29-NOV-2005 13:20:26")) {
            correctionIndex = 0; // No Correction is Applied
        } else if (getTimeInMillis(refTime) >= getTimeInMillis("29-NOV-2005 13:20:26") &&
                getTimeInMillis(refTime) < getTimeInMillis("18-DEC-2006 20:14:15")) {
            correctionIndex = 1; // Exponential Drift Correction is Applied
        } else {
            correctionIndex = 2; // Thin Film Drift Correction is Applied
        }
        return correctionIndex;
    }

    /**
     * This method performs a drift correction using a look up table to obtain the drift measurement for a
     * given channel and acquisition time.
     *
     * @param t           - acquisition time
     * @param ati         - acquisition time index in lookup table
     * @param t1          - time in lookup table previous to acquisition time
     * @param t2          - time in lookup table next to acquisition time
     * @param iChannel    - input channel
     * @param reflectance - input reflectance
     *
     * @return correctedReflectance
     */
    private double applyDriftCorrection(double t, int ati, double t1, double t2, int iChannel, double reflectance) {
        double correctedReflectance;
        double drift = 1.0;

        double y1;
        double y2;

        switch (iChannel) {
            case 0:
                y1 = driftTable.getDrift560()[ati];
                y2 = driftTable.getDrift560()[ati + 1];
                drift = linearInterpol(t, t1, t2, y1, y2);
                break;
            case 1:
                y1 = driftTable.getDrift670()[ati];
                y2 = driftTable.getDrift670()[ati + 1];
                drift = linearInterpol(t, t1, t2, y1, y2);
                break;
            case 2:
                y1 = driftTable.getDrift870()[ati];
                y2 = driftTable.getDrift870()[ati + 1];
                drift = linearInterpol(t, t1, t2, y1, y2);
                break;
            case 3:
                y1 = driftTable.getDrift1600()[ati];
                y2 = driftTable.getDrift1600()[ati + 1];
                drift = linearInterpol(t, t1, t2, y1, y2);
            default:
                break;
        }

        correctedReflectance = reflectance / drift;

        return correctedReflectance;
    }

    /**
     * This method provides a simple linear interpolation
     *
     * @param x  , position in [x1,x2] to interpolate at
     * @param x1 , left neighbour of x
     * @param x2 , right neighbour of x
     * @param y1 , y(x1)
     * @param y2 , y(x2)
     *
     * @return double z = y(x), the interpolated value
     */
    private double linearInterpol(double x, double x1, double x2, double y1, double y2) {
        double z;

        if (x1 == x2) {
            z = y1;
        } else {
            final double slope = (y2 - y1) / (x2 - x1);
            z = y1 + slope * (x - x1);
        }

        return z;
    }

    private int getAcquisitionTimeIndex(String acquisitionTime) {
        int acquisitionTimeIndex = -1;

        for (int i = 0; i < driftTableLength; i++) {
            if (getTimeInMillis(acquisitionTime) < getTimeInMillis(driftTable.getDate()[i])) {
                acquisitionTimeIndex = i;
                break;
            }
        }

        return acquisitionTimeIndex;
    }

    private boolean checkAcquisitionTimeRange(String acquisitionTime) throws OperatorException {
        final String envisatLaunch = "01-MAR-2002 00:00:00";

        if (getTimeInMillis(acquisitionTime) < getTimeInMillis(envisatLaunch)) {
            logger.log(Level.WARNING,
                       "Acquisition time " + acquisitionTime + " before ENVISAT launch date - no recalibration possible.");
            return false;
//            throw new OperatorException("ERROR: Acquisition time " + acquisitionTime + " before ENVISAT launch date.\n");
        }

        String driftTableStartDate = driftTable.getDate()[0];
        if (getTimeInMillis(acquisitionTime) < getTimeInMillis(driftTableStartDate)) {
            logger.log(Level.WARNING,
                       "Acquisition time " + acquisitionTime + " after last time of drift table - no recalibration possible.");
            return false;
//            throw new OperatorException(
//                    "ERROR: Acquisition time " + acquisitionTime + " before start time of drift table.\n");
        }

        String driftTableEndDate = driftTable.getDate()[driftTableLength - 1];
        if (getTimeInMillis(acquisitionTime) > getTimeInMillis(driftTableEndDate)) {
            logger.log(Level.WARNING,
                       "Acquisition time " + acquisitionTime + " after last time of drift table - no recalibration possible.");
            return false;
//            throw new OperatorException(
//                    "ERROR: Acquisition time " + acquisitionTime + " after last time of drift table.\n");
        }
        return true;
    }

    /**
     * This method provides the time in milliseconds from a given time string.
     * Allowed formats:
     * - dd-MMM-yyyy hh:mm:ss
     * - dd-mm-yyyy hh:mm:ss
     *
     * @param timeString the time string
     *
     * @return the time in millis
     */
    private long getTimeInMillis(String timeString) {
        long driftTableTime;

        Calendar driftTableDate = Calendar.getInstance();
        if (Character.isDigit(timeString.charAt(3))) {
            driftTableDate.set(Calendar.YEAR, Integer.parseInt(timeString.substring(6, 10)));
            String driftTableMonth = timeString.substring(3, 5);
            driftTableDate.set(Calendar.MONTH, Integer.parseInt(driftTableMonth));
        } else {
            driftTableDate.set(Calendar.YEAR, Integer.parseInt(timeString.substring(7, 11)));
            String driftTableMonth = timeString.substring(3, 6);
            String month = months.get(driftTableMonth);
            driftTableDate.set(Calendar.MONTH, Integer.parseInt(month));
        }
        driftTableDate.set(Calendar.DAY_OF_MONTH, Integer.parseInt(timeString.substring(0, 2)));

        driftTableTime = driftTableDate.getTimeInMillis();
        return driftTableTime;
    }

    /**
     * This method converts a given date to a Julian date
     *
     * @param year  - year of given date
     * @param month - month of given date
     * @param day   - day of given date
     *
     * @return julian
     */
    @SuppressWarnings("unused")
	private static double toJulian(int year, int month, int day) {
        int julianYear = year;
        if (year < 0) julianYear++;
        int julianMonth = month;
        if (month > 2) {
            julianMonth++;
        } else {
            julianYear--;
            julianMonth += 13;
        }

        double julian = (java.lang.Math.floor(365.25 * julianYear)
                + java.lang.Math.floor(30.6001 * julianMonth) + day + 1720995.0);
        if (day + 31 * (month + 12 * year) >= JGREG) {
            // change over to Gregorian calendar
            int ja = (int) (0.01 * julianYear);
            julian += 2 - ja + (0.25 * ja);
        }
        return java.lang.Math.floor(julian);
    }

    private static boolean isTargetBandValid(Band targetBand) {
        return targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_1600_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0870_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0670_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0550_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_1600_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0870_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0670_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0550_BAND_NAME);
    }

    private boolean isTargetBandSelected(Band targetBand) {
        return (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_1600_BAND_NAME) &&
                recalibrateNadir1600) ||
                (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0870_BAND_NAME) &&
                        recalibrateNadir0870) ||
                (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0670_BAND_NAME) &&
                        recalibrateNadir0670) ||
                (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0550_BAND_NAME) &&
                        recalibrateNadir0550) ||
                (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_1600_BAND_NAME) &&
                        recalibrateFward1600) ||
                (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0870_BAND_NAME) &&
                        recalibrateFward0870) ||
                (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0670_BAND_NAME) &&
                        recalibrateFward0670) ||
                (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0550_BAND_NAME) &&
                        recalibrateFward0550);
    }

    private static int getChannelIndex(Band targetBand) {
        int channelIndex = -1;

        if (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0550_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0550_BAND_NAME)) {
            channelIndex = CHANNEL550;
        }
        if (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0670_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0670_BAND_NAME)) {
            channelIndex = CHANNEL670;
        }
        if (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_0870_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_0870_BAND_NAME)) {
            channelIndex = CHANNEL870;
        }
        if (targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_NADIR_1600_BAND_NAME) ||
                targetBand.getName().equals(EnvisatConstants.AATSR_L1B_REFLEC_FWARD_1600_BAND_NAME)) {
            channelIndex = CHANNEL1600;
        }

        return channelIndex;
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        Rectangle rectangle = targetTile.getRectangle();

        pm.beginTask("Processing frame...", rectangle.height);
        try {
            Tile sourceTile = getSourceTile(sourceProduct.getBand(targetBand.getName()), rectangle, pm);
            if (targetBand.isFlagBand()) {
                for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                    for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                        if (pm.isCanceled()) {
                            break;
                        }
                        targetTile.setSample(x, y, sourceTile.getSampleInt(x, y));
                    }
                    pm.worked(1);
                }
            } else if (doRecalibration && isTargetBandSelected(targetBand) && isTargetBandValid(targetBand)) {
                // apply recalibration

//				Tile isInvalid = getSourceTile(invalidBand, rectangle, pm); // TODO if necessary

                String vc1Filename = sourceProduct.getMetadataRoot().getElement(
                        "DSD").getElement("DSD.31").getAttribute("FILE_NAME")
                        .getData().getElemString();
                String gc1Filename = sourceProduct.getMetadataRoot().getElement(
                        "DSD").getElement("DSD.32").getAttribute("FILE_NAME")
                        .getData().getElemString();

                final int ati = getAcquisitionTimeIndex(sensingStart);
                final double atiPrev = (double) getTimeInMillis(driftTable.getDate()[ati]);
                final double atiNext = (double) getTimeInMillis(driftTable.getDate()[ati + 1]);

                final double sensingStartMillis = getTimeInMillis(sensingStart);

                int iChannel = getChannelIndex(targetBand);

                int removeDriftCorrIndex = getRemoveDriftCorrectionIndex(vc1Filename);

                // acquisition time difference in days
                double acquisitionTimeDiff = (getTimeInMillis(sensingStart) - getTimeInMillis(
                        envisatLaunch)) / (1.E3 * SECONDS_PER_DAY);

                for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                    for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                        if (pm.isCanceled()) {
                            break;
                        }
                        // TODO: activate if invalid flag has been defined
                        //					if (isInvalid.getSampleBoolean(x, y)) {
                        //						targetTile.setSample(x, y, 0);
                        //					} else {
                        final double reflectance = sourceTile.getSampleDouble(x, y);

                        // Correct for nonlinearity
                        double reflectanceCorrected1 = reflectance;
                        if (iChannel == CHANNEL1600) {
                            reflectanceCorrected1 = getV16NonlinearityCorrectedReflectance(gc1Filename, reflectance);
                        }
                        // Remove existing long term drift
                        double reflectanceCorrected2;
                        reflectanceCorrected2 = removeDriftCorrection(iChannel, removeDriftCorrIndex,
                                                                      acquisitionTimeDiff, reflectanceCorrected1);

                        // Apply new long term drift corrections
                        double reflectanceCorrectedAll;
                        reflectanceCorrectedAll = applyDriftCorrection(sensingStartMillis, ati, atiPrev, atiNext,
                                                                       iChannel, reflectanceCorrected2);

                        targetTile.setSample(x, y, reflectanceCorrectedAll);
                    }
                    pm.worked(1);
                }
            } else {
                // band is either:
                //		- brightness temperature
                //		- reflectance which shall not be recalibrated
                // --> just copy from source
                for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                    for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                        if (pm.isCanceled()) {
                            break;
                        }
                        targetTile.setSample(x, y, sourceTile.getSampleDouble(x, y));
                    }
                    pm.worked(1);
                }
            }
        } catch (Exception e) {
            throw new OperatorException("AATSR Recalibration Failed: \n" + e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

    private class DriftTable {
        private String[] date = new String[DRIFT_TABLE_MAX_LENGTH];
        private double[] drift560 = new double[DRIFT_TABLE_MAX_LENGTH];
        private double[] drift670 = new double[DRIFT_TABLE_MAX_LENGTH];
        private double[] drift870 = new double[DRIFT_TABLE_MAX_LENGTH];
        private double[] drift1600 = new double[DRIFT_TABLE_MAX_LENGTH];

        public String[] getDate() {
            return date;
        }

        public double[] getDrift560() {
            return drift560;
        }

        public double[] getDrift670() {
            return drift670;
        }

        public double[] getDrift870() {
            return drift870;
        }

        public double[] getDrift1600() {
            return drift1600;
        }

        public void setDate(int index, String value) {
            date[index] = value;
        }

        public void setDrift560(int index, double value) {
            drift560[index] = value;
        }

        public void setDrift670(int index, double value) {
            drift670[index] = value;
        }

        public void setDrift870(int index, double value) {
            drift870[index] = value;
        }

        public void setDrift1600(int index, double value) {
            drift1600[index] = value;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RecalibrateAATSRReflectancesOp.class);
        }
    }
}
