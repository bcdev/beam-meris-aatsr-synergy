/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.synergy.ui;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.synergy.operators.LaplaceInterpolationOp;
import org.esa.beam.visat.VisatApp;
import org.esa.beam.visat.actions.AbstractVisatAction;

/**
 * Action class for aerosol interpolation.
 *
 * @author Andreas Heckel
 * @version $Revision: 8041 $ $Date: 2010-01-20 17:23:15 +0100 (Mi, 20 Jan 2010) $
 */
public class LaplaceInterpolationAction extends AbstractVisatAction {

    @Override
    public void actionPerformed(CommandEvent event) {
        final DefaultSingleTargetProductDialog dialog =
                new DefaultSingleTargetProductDialog(OperatorSpi.getOperatorAlias(LaplaceInterpolationOp.class),
                        getAppContext(),
                        "Synergy Interpolation",
                        "performLaplaceIntp");

        dialog.setTargetProductNameSuffix("_INTP");
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
