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
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;

import java.awt.*;
import java.util.Map;

/**
 * Operator for computing TOA reflectances.
 *
 * @author Ralf Quast
 * @version $Revision: 5306 $ $Date: 2009-05-15 18:42:38 +0200 (Fr, 15 Mai 2009) $
 */
@OperatorMetadata(alias = "synergy.ComputeToaReflectances",
                  version = "1.0-SNAPSHOT",
                  authors = "Ralf Quast, Olaf Danne",
                  copyright = "(c) 2008 by Brockmann Consult",
                  description = "Computes TOA reflectances from coregistered MERIS/AATSR products.")
public class ComputeToaReflectancesOp extends Operator {
	@SuppressWarnings("unused")
	@SourceProduct(alias = "source",
                   bands = {"radiance_1", "radiance_2", "radiance_3"},
                   label = "Name (MERIS/AATSR collocated product)",
                   description = "Select a MERIS/AATSR collocated product")
    private Product sourceProduct;
	@SuppressWarnings("unused")
	@TargetProduct(description = "The target product. Contains the TOA reflectances.")
    private Product targetProduct;
    @Parameter(defaultValue = "false",
               label = "Copy TOA radiances")
    boolean copyToaRadiances;

    @Override
    public void initialize() throws OperatorException {
        targetProduct = new Product("TOA_REFL", "TOA_REFL", 1, 1);
    }

    @Override
    public void dispose() {
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle,
                                 ProgressMonitor pm) throws OperatorException {
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ComputeToaReflectancesOp.class);
        }
    }
}
