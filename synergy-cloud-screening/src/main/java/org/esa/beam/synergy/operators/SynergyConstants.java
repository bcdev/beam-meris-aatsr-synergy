package org.esa.beam.synergy.operators;

//import java.util.HashMap;
//import java.util.Map;

public class SynergyConstants {

    // Constants
    public static double TAU_ATM = 0.222029504023959;
    
    // Synergy product types
    public static final String SYNERGY_RR_PRODUCT_TYPE_NAME = "SYNERGY_RR_";    
    public static final String SYNERGY_FR_PRODUCT_TYPE_NAME = "SYNERGY_FR_";    
    public static final String SYNERGY_FS_PRODUCT_TYPE_NAME = "SYNERGY_FS_";    

    // Meris bands
    public static final int[] MERIS_VIS_BANDS = { 1, 2, 3, 4, 5, 6, 7, 8 };
    public static final int[] MERIS_NIR_BANDS = { 9, 10, 12, 13, 14 };
    
    // Bitmask flags
    public static final int FLAGMASK_SHADOW       = 1;
    public static final int FLAGMASK_SNOW_FILLED  = 2;
    public static final int FLAGMASK_SNOW         = 4;
    public static final int FLAGMASK_CLOUD_FILLED = 8;
    public static final int FLAGMASK_CLOUD        = 16;
    
    public static final String FLAGNAME_SHADOW       = "SHADOW";
    public static final String FLAGNAME_SNOW_FILLED  = "SNOW_FILLED";
    public static final String FLAGNAME_SNOW         = "SNOW";
    public static final String FLAGNAME_CLOUD_FILLED = "CLOUD_FILLED";
    public static final String FLAGNAME_CLOUD        = "CLOUD";

    // Strings to detect reduced or full resolution products
    public static final String RR_STR  = "_RR_";
    public static final String FR_STR  = "_FR";
    public static final String FS_STR  = "_FS";
    public static final String FSG_STR = "_FSG";
    
    public static final String MERIS_RADIANCE     = "radiance";
    public static final String MERIS_REFLECTANCE  = "reflectance";
    public static final String AATSR_REFLEC_NADIR = "reflec_nadir";
    public static final String AATSR_REFLEC_FWARD = "reflec_fward";
    public static final String AATSR_BTEMP_NADIR  = "btemp_nadir";
    public static final String AATSR_BTEMP_FWARD  = "btemp_fward";
    public static final String DEM_ELEVATION      = "dem_elevation";
    // Our synergy product has a different name for CTP than the original
    public static final String ENVISAT_CTP        = "cloud_top_press";
    public static final String SYNERGY_CTP        = "p_ctp";

    public static final String FLAG_LAND_OCEAN     = "LAND_OCEAN";
    public static final String FLAG_COASTLINE      = "COASTLINE";
    public static final String FLAG_INVALID        = "INVALID";
    public static final String FLAG_SCAN_ABSENT    = "SCAN_ABSENT";
    public static final String FLAG_ABSENT         = "ABSENT";
    public static final String FLAG_NOT_DECOMPR    = "NOT_DECOMPR";
    public static final String FLAG_NO_SIGNAL      = "NO_SIGNAL";
    public static final String FLAG_OUT_OF_RANGE   = "OUT_OF_RANGE";
    public static final String FLAG_NO_CALIB_PARAM = "NO_CALIB_PARAM";
    public static final String FLAG_UNFILLED       = "UNFILLED";

    // Feature band names
    public static final String F_BRIGHTENESS_NIR   = "f_brightness_nir";
    public static final String F_WHITENESS_NIR     = "f_whiteness_nir";
    public static final String F_BRIGHTENESS_VIS   = "f_brightness_vis";
    public static final String F_WHITENESS_VIS     = "f_whiteness_vis";
    public static final String F_WATER_VAPOR_ABS   = "f_water_vapor_abs";
    public static final String F_761_754_865_RATIO = "f_761-754-865ratio";
    public static final String F_443_754_RATIO     = "f_443-754ratio";
    public static final String F_870_670_RATIO     = "f_870-670ratio";
    public static final String F_11_12_DIFF        = "f_11-12diff";
    public static final String F_865_890_NDSI      = "f_865-890NDSI";
    public static final String F_555_1600_NDSI     = "f_555-1600NDSI";
    public static final String F_COASTLINE         = "f_coastline";
    public static final String F_SURF_PRESS        = "f_surf_press";
    public static final String F_SURF_PRESS_DIFF   = "f_surf_press_diff";

    // Classification steps intermediate bands
    public static final String B_COAST_ERODED = "coast_eroded";

    // Final cloud mask band names
    public static final String B_CLOUDMASK        = "cloudmask";
    public static final String B_CLOUDMASK_FILLED = "cloudmask_filled";
    public static final String B_SNOWMASK         = "snowmask";
    public static final String B_SNOWMASK_FILLED  = "snowmask_filled";
    public static final String B_SHADOWMASK       = "shadowmask";
    public static final String B_CLOUD_COMB       = "nn_comb_cloud";
    public static final String B_SNOW_COMB        = "nn_comb_snow";
    public static final String B_CLOUDINDEX       = "cloud_index_synergy";
    public static final String B_CLOUDFLAGS       = "cloud_flags_synergy";

    // Synergy NNs
    public static final String nn_global_synergy_nadir = "nn_global_synergy_nadir";
    public static final String nn_global_synergy_dual  = "nn_global_synergy_dual";
    public static final String nn_land_synergy_nadir   = "nn_land_synergy_nadir";
    public static final String nn_land_synergy_dual    = "nn_land_synergy_dual";
    public static final String nn_ocean_synergy_nadir  = "nn_ocean_synergy_nadir";
    public static final String nn_ocean_synergy_dual   = "nn_ocean_synergy_dual";
    public static final String nn_snow_synergy_nadir   = "nn_snow_synergy_nadir";
    public static final String nn_snow_synergy_dual    = "nn_snow_synergy_dual";
    public static final String nn_index_synergy_nadir  = "nn_index_synergy_nadir";
    public static final String nn_index_synergy_dual   = "nn_index_synergy_dual";
    // For band names (land and ocean are combined into local)
    public static final String nn_local_synergy_nadir = "nn_local_synergy_nadir";
    public static final String nn_local_synergy_dual  = "nn_local_synergy_dual";
    
    public static final String[] nn_synergy = {
    	nn_global_synergy_nadir, nn_global_synergy_dual,
    	nn_land_synergy_nadir, nn_land_synergy_dual,
    	nn_ocean_synergy_nadir, nn_ocean_synergy_dual,
        nn_snow_synergy_nadir, nn_snow_synergy_dual,
        nn_index_synergy_nadir, nn_index_synergy_dual
    };
 
    // Single instrument NNs
    public static final String nn_global_meris_nadir = "nn_global_meris_nadir";
    public static final String nn_global_aatsr_nadir = "nn_global_aatsr_nadir";
    public static final String nn_global_aatsr_dual  = "nn_global_aatsr_dual";
    public static final String nn_land_meris_nadir   = "nn_land_meris_nadir";
    public static final String nn_land_aatsr_nadir   = "nn_land_aatsr_nadir";
    public static final String nn_land_aatsr_dual    = "nn_land_aatsr_dual";
    public static final String nn_ocean_meris_nadir  = "nn_ocean_meris_nadir";
    public static final String nn_ocean_aatsr_nadir  = "nn_ocean_aatsr_nadir";
    public static final String nn_ocean_aatsr_dual   = "nn_ocean_aatsr_dual";
    public static final String nn_snow_meris_nadir   = "nn_snow_meris_nadir";
    public static final String nn_snow_aatsr_nadir   = "nn_snow_aatsr_nadir";
    public static final String nn_snow_aatsr_dual    = "nn_snow_aatsr_dual";
    // For band names (land and ocean are combined into local)
    public static final String nn_local_meris_nadir = "nn_local_meris_nadir";
    public static final String nn_local_aatsr_nadir = "nn_local_aatsr_nadir";
    public static final String nn_local_aatsr_dual  = "nn_local_aatsr_dual";
    
    public static final String[] nn_single = {
        nn_global_meris_nadir, nn_global_aatsr_nadir, nn_global_aatsr_dual,
        nn_land_meris_nadir, nn_land_aatsr_nadir, nn_land_aatsr_dual,
        nn_ocean_meris_nadir, nn_ocean_aatsr_nadir, nn_ocean_aatsr_dual,
        nn_snow_meris_nadir, nn_snow_aatsr_nadir, nn_snow_aatsr_dual
    };
}
