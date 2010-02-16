package org.esa.beam.synergy.ui;

import org.esa.beam.visat.actions.AbstractVisatAction;
import org.esa.beam.visat.VisatApp;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.framework.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.synergy.operators.RetrieveAerosolOceanOp;

/**
 * Action class for aerosol retrieval over water within MERIS/AATSR Synergy project.
 *
 * @author Andreas Heckel, Olaf Danne
 * @version $Revision: 8041 $ $Date: 2010-01-20 17:23:15 +0100 (Mi, 20 Jan 2010) $
 *
 */
public class RetrieveAerosolOceanAction extends AbstractVisatAction {
    @Override
    public void actionPerformed(CommandEvent commandEvent) {
        final DefaultSingleTargetProductDialog dialog =
                new DefaultSingleTargetProductDialog(OperatorSpi.getOperatorAlias(RetrieveAerosolOceanOp.class),
                        getAppContext(),
                        "Synergy Aerosol over Ocean",
                        "synergyAerosolOcean");
        dialog.setTargetProductNameSuffix("_AERO_OCEAN");
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
