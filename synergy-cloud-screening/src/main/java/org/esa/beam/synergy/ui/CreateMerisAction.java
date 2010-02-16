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
import org.esa.beam.synergy.operators.CreateMerisOp;
import org.esa.beam.visat.VisatApp;
import org.esa.beam.visat.actions.AbstractVisatAction;

/**
 * Action for creating MERIS product.
 *
 * @author Jordi Munyoz-Mari and Luis Gomez-Chova
 * @version $Revision: 5790 $ $Date: 2009-06-29 16:12:20 +0200 (Mo, 29 Jun 2009) $
 * @since BEAM 4.5
 */
public class CreateMerisAction extends AbstractVisatAction {
    @Override
    public void actionPerformed(CommandEvent commandEvent) {
        final DefaultSingleTargetProductDialog dialog =
                new DefaultSingleTargetProductDialog(OperatorSpi.getOperatorAlias(CreateMerisOp.class),
                        getAppContext(),
                        "Prepare MERIS product",
                        "synergyPrepareMERIS");
        dialog.setTargetProductNameSuffix("_MERIS");
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
