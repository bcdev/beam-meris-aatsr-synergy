/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.operators;

/**
 * Constants used in Synergy aerosol retrieval
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8111 $ $Date: 2010-01-27 17:54:34 +0000 (Mi, 27 Jan 2010) $
 */
public class RetrieveAerosolConstants {

    // Constants
    public static final String SOIL_SPEC_PARAM_NAME    = "soilspec";
    public static final String SOIL_SPEC_PARAM_DEFAULT = "spec_soil.dat";
    public static final String SOIL_SPEC_PARAM_LABEL   = "Soil surface reflectance spectrum";
    public static final String SOIL_SPEC_PARAM_DESCRIPTION = "File containing soil surface reflectance spectrum";

    public static final String VEG_SPEC_PARAM_NAME    = "vegspec";
    public static final String VEG_SPEC_PARAM_DEFAULT = "spec_veg.dat";
    public static final String VEG_SPEC_PARAM_LABEL   = "Vegetation surface reflectance spectrum";
    public static final String VEG_SPEC_PARAM_DESCRIPTION = "File containing vegetation surface reflectance spectrum";
    
    public static final String LUT_PATH_PARAM_NAME    = "lutpath";
    public static final String LUT_PATH_PARAM_LABEL = "Path to LUTs for ocean aerosol algorithm";
    public static final String LUT_PATH_PARAM_DESCRIPTION = "File path to LookUpTables root directory";
    public static final String LUT_PATH_PARAM_DEFAULT = "C:/synergy/aerosolLUTs";
    
//    public static final String LUT_LAND_PATH_PARAM_DEFAULT = "e:/model_data/momo/bin";
//    public static final String LUT_PATH_PARAM_DEFAULT = "e:/model_data/momo";
    public static final String[] LUT_LAND_AATSR_WAVELEN = {"00550.00","00665.00","00865.00","01610.00"};
    public static final String[] LUT_LAND_MERIS_WAVELEN = {"00412.00","00442.00","00490.00","00510.00","00560.00",
                                                      "00620.00","00665.00","00681.00","00708.00","00753.00",
                                                      "00778.00","00865.00","00885.00"};
    
    public static final String AEROSOL_MODEL_PARAM_NAME    = "aerosolModels";
    public static final String AEROSOL_MODEL_PARAM_DEFAULT = "8,20,28";
    public static final String AEROSOL_MODEL_PARAM_LABEL   = "List of land aerosol models";
    public static final String AEROSOL_MODEL_PARAM_DESCRIPTION = "Comma sep. list of aerosol model identifiers";

    public static final String OUTPUT_PRODUCT_NAME_NAME = "targetname";
    public static final String OUTPUT_PRODUCT_NAME_DEFAULT = "SYNERGY LAND AEROSOL";
    public static final String OUTPUT_PRODUCT_NAME_DESCRIPTION = "Product name of the target data set";
    public static final String OUTPUT_PRODUCT_NAME_LABEL = "Product name";

    public static final String OUTPUT_PRODUCT_TYPE_NAME = "targettype";
    public static final String OUTPUT_PRODUCT_TYPE_DEFAULT = "AEROSOL";
    public static final String OUTPUT_PRODUCT_TYPE_DESCRITPION = "Product type of the target data set";
    public static final String OUTPUT_PRODUCT_TYPE_LABEL = "Product type";

    // aot output
    public static final String OUTPUT_AOT_BAND_NAME = "aot";
    public static final String OUTPUT_AOT_BAND_DESCRIPTION = "MERIS AATSR Synergy AOT";
    public static final double OUTPUT_AOT_BAND_NODATAVALUE = -1.0;
    public static final boolean OUTPUT_AOT_BAND_NODATAVALUE_USED = Boolean.TRUE;

    // ang output
    public static final String OUTPUT_ANG_BAND_NAME = "ang";
    public static final String OUTPUT_ANG_BAND_DESCRIPTION = "MERIS AATSR Synergy Angstrom Parameter";
    public static final double OUTPUT_ANG_BAND_NODATAVALUE = -1.0;
    public static final boolean OUTPUT_ANG_BAND_NODATAVALUE_USED = Boolean.TRUE;

    // aot uncertainty output
    public static final String OUTPUT_AOTERR_BAND_NAME = "aot_uncertainty";
    public static final String OUTPUT_AOTERR_BAND_DESCRIPTION = "MERIS AATSR Synergy uncertainty of AOT";
    public static final double OUTPUT_AOTERR_BAND_NODATAVALUE = -1.0;
    public static final boolean OUTPUT_AOTERR_BAND_NODATAVALUE_USED = Boolean.TRUE;

    // ang uncertainty output
    public static final String OUTPUT_ANGERR_BAND_NAME = "ang_uncertainty";
    public static final String OUTPUT_ANGERR_BAND_DESCRIPTION = "MERIS AATSR Synergy uncertainty of Angstrom Parameter";
    public static final double OUTPUT_ANGERR_BAND_NODATAVALUE = -1.0;
    public static final boolean OUTPUT_ANGERR_BAND_NODATAVALUE_USED = Boolean.TRUE;

    // land aerosol model output
    public static final String OUTPUT_AOTMODEL_BAND_NAME = "land_aerosol_model";
    public static final String OUTPUT_AOTMODEL_BAND_DESCRIPTION = "MERIS AATSR Synergy LAnd Aerosol Model";
    public static final int OUTPUT_AOTMODEL_BAND_NODATAVALUE = -1;
    public static final boolean OUTPUT_AOTMODEL_BAND_NODATAVALUE_USED = Boolean.TRUE;

    // glint output
    public static final String OUTPUT_GLINT_BAND_NAME = "glint";
    public static final String OUTPUT_GLINT_BAND_DESCRIPTION = "Glint retrieval for first band used (debug output)";
    public static final double OUTPUT_GLINT_BAND_NODATAVALUE = -1.0;
    public static final boolean OUTPUT_GLINT_BAND_NODATAVALUE_USED = Boolean.TRUE;

    public static final String INPUT_PRESSURE_BAND_NAME = "atm_press";
    public static final String INPUT_OZONE_BAND_NAME = "ozone";
    public static final double[] o3CorrSlopeAatsr = {-0.183498, -0.109118, 0.0, 0.0};
    public static final double[] o3CorrSlopeMeris = {0.00, -0.00494535, -0.0378441, -0.0774214, -0.199693, -0.211885, -0.0986805, -0.0675948, -0.0383019, -0.0177211, 0.00, 0.00, 0.00};
    //public static final double wvCorrSlopeAatsr = -0.00552339;
    public static final double[] wvCorrSlopeAatsr = {0.00, -0.00493235, -0.000441272, -0.000941457};
    public static final double[] wvCorrSlopeMeris = {0.00, 0.00, 0.00, 0.00, 0.00, 0.00, -0.000416283, -0.000326704, -0.0110851, 0.00, -0.000993413, -0.000441272, -0.00286989};

    // windspeed output
    public static final String OUTPUT_WS_BAND_NAME = "windspeed";
    public static final String OUTPUT_WS_BAND_DESCRIPTION = "Windspeed retrieval from Glint algorithm (debug output)";
    public static final double OUTPUT_WS_BAND_NODATAVALUE = -1.0;
    public static final boolean OUTPUT_WS_BAND_NODATAVALUE_USED = Boolean.TRUE;

    // Surface Directional Reflectance Output
    public static final String OUTPUT_SDR_BAND_NAME = "SynergySDR";
    public static final String OUTPUT_SDR_BAND_DESCRIPTION = "Surface Directional Reflectance Band";
    public static final double OUTPUT_SDR_BAND_NODATAVALUE = -1.0;
    public static final boolean OUTPUT_SDR_BAND_NODATAVALUE_USED = Boolean.TRUE;


    public static final String INPUT_BANDS_PREFIX_MERIS = "reflectance_";
    public static final String INPUT_BANDS_PREFIX_AATSR_NAD = "reflec_nadir";
    public static final String INPUT_BANDS_PREFIX_AATSR_FWD = "reflec_fward";

    public static final String INPUT_BANDS_SUFFIX_AATSR = "AATSR";
    public static final String INPUT_BANDS_SUFFIX_MERIS = "MERIS";
    // todo: for FUB demo products only. change back later!
//    public static final String INPUT_BANDS_SUFFIX_AATSR = "S";
//    public static final String INPUT_BANDS_SUFFIX_MERIS = "M";

    public static final int[] EXCLUDE_INPUT_BANDS_MERIS = {11,15};
    public static final int[] EXCLUDE_INPUT_BANDS_AATSR = null;
    static String OUTPUT_ERR_BAND_NAME;

    public static final float[] refractiveIndex = new float[]{1.3295f,1.329f,1.31f,1.3295f,1.31f,1.3295f};
    public static final float[] rhoFoam = new float[]{0.2f, 0.2f, 0.1f, 0.2f, 0.1f, 0.2f};

    //aerosol flag constants
    public static final String aerosolFlagCodingName = "aerosol_land_flags";
    public static final String aerosolFlagCodingDesc = "Aerosol Retrieval Flags";
    public static final String flagCloudyName = "aerosol_cloudy";
    public static final String flagOceanName = "aerosol_ocean";
    public static final String flagSuccessName = "aerosol_successfull";
    public static final String flagBorderName = "aerosol_border";
    public static final String flagFilledName = "aerosol_filled";
    public static final String flagNegMetricName = "negative_metric";
    public static final String flagAotLowName = "aot_low";
    public static final String flagErrHighName = "aot_error_high";
    public static final String flagCoastName = "aerosol_coastline";
    public static final String flagCloudyDesc = "no land aerosol retrieval due to clouds";
    public static final String flagOceanDesc = "no land aerosol retrieval due to ocean";
    public static final String flagSuccessDesc = "land aerosol retrieval performed";
    public static final String flagBorderDesc = "land aerosol retrieval average for border pixel";
    public static final String flagFilledDesc = "no aerosol retrieval, pixel values interpolated";
    public static final String flagNegMetricDesc = "negative_metric";
    public static final String flagAotLowDesc = "aot_low";
    public static final String flagErrHighDesc = "aot_error_high";
    public static final String flagCoastDesc = "Coast Line Pixel";
    public static final int oceanMask = 1;
    public static final int cloudyMask = 2;
    public static final int successMask = 4;
    public static final int borderMask = 8;
    public static final int filledMask = 16;
    public static final int negMetricMask = 32;
    public static final int aotLowMask = 64;
    public static final int errHighMask = 128;
    public static final int coastMask = 256;


    // this is preliminary!
    public static float MERIS_12_SOLAR_FLUX = 930.0f;
    public static float MERIS_13_SOLAR_FLUX = 902.0f;
    public static float MERIS_14_SOLAR_FLUX = 870.0f;

    public static final String CONFID_NADIR_FLAGS_AATSR = "confid_flags_nadir_AATSR";
    public static final String CONFID_FWARD_FLAGS_AATSR = "confid_flags_fward_AATSR";
    public static final String CLOUD_NADIR_FLAGS_AATSR = "cloud_flags_nadir_AATSR";
    public static final String CLOUD_FWARD_FLAGS_AATSR = "cloud_flags_fward_AATSR";

    public static final String L1_FLAGS_MERIS = "l1_flags_MERIS";
    public static final String CLOUD_FLAG_MERIS = "cloud_flag_MERIS";

}
