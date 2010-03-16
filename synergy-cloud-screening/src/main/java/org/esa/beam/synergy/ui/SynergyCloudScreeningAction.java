package org.esa.beam.synergy.ui;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.synergy.operators.SynergyCloudScreeningOp;
import org.esa.beam.visat.VisatApp;
import org.esa.beam.visat.actions.AbstractVisatAction;

import java.awt.Dimension;

public class SynergyCloudScreeningAction extends AbstractVisatAction {
    @Override
    public void actionPerformed(CommandEvent commandEvent) {
        final DefaultSingleTargetProductDialog dialog =
                new DefaultSingleTargetProductDialog(
                		OperatorSpi.getOperatorAlias(SynergyCloudScreeningOp.class),
                        getAppContext(),
                        "Cloud  Screening",
                        "synergyhelp");
        dialog.setTargetProductNameSuffix("_CS");
        dialog.getJDialog().setPreferredSize(new Dimension(600, 500));
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
