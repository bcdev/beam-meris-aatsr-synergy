/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.util;

import java.util.Comparator;
import org.esa.beam.framework.datamodel.Band;

/**
 * TODO: take from elsewhere (probably duplicated)
 *
 * @author Andreas Heckel
 * @version $Revision: 8041 $ $Date: 2010-01-20 16:23:15 +0000 (Mi, 20 Jan 2010) $
 */
public class WavelengthComparator implements Comparator<Band>{

    public WavelengthComparator() {
    }

    @Override
    public int compare(Band o1, Band o2) {
        float wl1 = o1.getSpectralWavelength();
        float wl2 = o2.getSpectralWavelength();
        return Float.compare(wl1, wl2);
    }

}
