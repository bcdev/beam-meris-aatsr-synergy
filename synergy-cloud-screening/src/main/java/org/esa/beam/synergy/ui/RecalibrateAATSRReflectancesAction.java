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
package org.esa.beam.synergy.ui;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.synergy.operators.RecalibrateAATSRReflectancesOp;
import org.esa.beam.visat.VisatApp;
import org.esa.beam.visat.actions.AbstractVisatAction;

/**
 * Action for recalibrating AATSR reflectances.
 *
 * @author Olaf Danne
 * @version $Revision: 5655 $ $Date: 2009-06-16 19:53:48 +0200 (Di, 16 Jun 2009) $
 * @since BEAM 4.2
 */
public class RecalibrateAATSRReflectancesAction extends AbstractVisatAction {
    @Override
    public void actionPerformed(CommandEvent commandEvent) {
        final DefaultSingleTargetProductDialog dialog =
                new DefaultSingleTargetProductDialog(OperatorSpi.getOperatorAlias(RecalibrateAATSRReflectancesOp.class),
                        getAppContext(),
                        "AATSR Reflectance Recalibration",
                        "synergyAATSRReflectanceRecalibration");
        dialog.setTargetProductNameSuffix("_RECALIBRATED");
        dialog.show();
    }

    @Override
    public void updateState() {
        final Product selectedProduct = VisatApp.getApp().getSelectedProduct();
        final boolean enabled = selectedProduct == null || isValidProduct(selectedProduct);

        setEnabled(enabled);
    }

    private boolean isValidProduct(Product product) {
        return true;
    }
}
