package org.esa.beam.synergy.ui;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.synergy.operators.ClassifyFeaturesOp;
import org.esa.beam.visat.VisatApp;
import org.esa.beam.visat.actions.AbstractVisatAction;

/**
 * Action for classifying features.
 *
 * @author Ralf Quast
 * @version $Revision: 5655 $ $Date: 2009-06-16 19:53:48 +0200 (Di, 16 Jun 2009) $
 */
public class ClassifyFeaturesAction extends AbstractVisatAction {
    @Override
    public void actionPerformed(CommandEvent commandEvent) {
        final DefaultSingleTargetProductDialog dialog =
                new DefaultSingleTargetProductDialog(OperatorSpi.getOperatorAlias(ClassifyFeaturesOp.class),
                        getAppContext(),
                        "Feature Classification",
                        "synergyFeatureClassification");
        dialog.setTargetProductNameSuffix("_CLASS");
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
