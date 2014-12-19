package org.esa.beam.synergy.ui;

import com.bc.ceres.binding.PropertyContainer;
import com.bc.ceres.swing.TableLayout;
import com.bc.ceres.swing.binding.BindingContext;
import com.bc.ceres.swing.binding.PropertyPane;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.ui.SourceProductSelector;
import org.esa.beam.framework.gpf.ui.TargetProductSelector;
import org.esa.beam.framework.ui.AppContext;
import org.esa.beam.synergy.util.SynergyConstants;
import org.esa.beam.util.io.FileChooserFactory;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class SynergyForm extends JTabbedPane {

    private JCheckBox useForwardViewCheckBox;
    private JCheckBox computeCOTCheckBox;
    private JCheckBox computeSFCheckBox;
    private JCheckBox computeSHCheckBox;

    private JCheckBox computeOceanCheckBox;
    private JCheckBox computeLandCheckBox;
    private JCheckBox computeSurfaceReflectancesCheckBox;
    private JFormattedTextField soilSpecNameTextField;
    private JButton soilSpecFileChooserButton;
    private JFormattedTextField vegSpecNameTextField;
    private JButton vegSpecFileChooserButton;
    private JFormattedTextField aerosolModelStringTextField;
    private JFormattedTextField aveBlockTextField;

    private JLabel soilSpecNameLabel;
    private JLabel vegSpecNameLabel;
    private JLabel aerosolModelStringLabel;
    private JLabel aveBlockLabel;

    private JLabel landAerosolModelLabel;
    private ButtonGroup landAerosolModelGroup;
    private JRadioButton defaultLandAerosolRadioButton;
    private JRadioButton customLandAerosolRadioButton;
    private JFormattedTextField landAerosolModelTextField;
    private JLabel customLandAerosolLabel;
    private boolean customLandAerosolSelected;

    private ButtonGroup processingParamGroup;
    private JRadioButton doPreprocessingRadioButton;
    private JRadioButton doCloudScreeningRadioButton;
    private JRadioButton doAerosolRetrievalRadioButton;

    private TargetProductSelector targetProductSelector;

    private List<SourceProductSelector> sourceProductSelectorList;
    private Map<Field, SourceProductSelector> sourceProductSelectorMap;

    private AppContext appContext;
    private OperatorSpi operatorSpi;
    private SynergyModel synergyModel;
    private String synergyAuxdataHome;
    private ImageIcon infoIcon;

    public SynergyForm(AppContext appContext, OperatorSpi operatorSpi, SynergyModel synergyModel, TargetProductSelector targetProductSelector) {
       this.appContext = appContext;
       this.operatorSpi = operatorSpi;
       this.synergyModel = synergyModel;
       this.targetProductSelector = targetProductSelector;

       initComponents();
       bindComponents();
       updateUIState();
    }

    private void bindComponents() {
        final BindingContext bc = new BindingContext(synergyModel.getPropertyContainer());
//        bc.bind("createPreprocessingProduct", doPreprocessingRadioButton);
//        bc.bind("createCloudScreeningProduct", doCloudScreeningRadioButton);
//        bc.bind("createAerosolProduct", doAerosolRetrievalRadioButton);

        Map<AbstractButton, Object> createPreprocessingProductValueSet = new HashMap<AbstractButton, Object>(4);
        createPreprocessingProductValueSet.put(doPreprocessingRadioButton, true);
        createPreprocessingProductValueSet.put(doCloudScreeningRadioButton, false);
        createPreprocessingProductValueSet.put(doAerosolRetrievalRadioButton, false);
        bc.bind("createPreprocessingProduct", processingParamGroup, createPreprocessingProductValueSet);

        Map<AbstractButton, Object> createCloudScreeningProductValueSet = new HashMap<AbstractButton, Object>(4);
        createCloudScreeningProductValueSet.put(doPreprocessingRadioButton, false);
        createCloudScreeningProductValueSet.put(doCloudScreeningRadioButton, true);
        createCloudScreeningProductValueSet.put(doAerosolRetrievalRadioButton, false);
        bc.bind("createCloudScreeningProduct", processingParamGroup, createCloudScreeningProductValueSet);

        Map<AbstractButton, Object> createAerosolProductValueSet = new HashMap<AbstractButton, Object>(4);
        createAerosolProductValueSet.put(doPreprocessingRadioButton, false);
        createAerosolProductValueSet.put(doCloudScreeningRadioButton, false);
        createAerosolProductValueSet.put(doAerosolRetrievalRadioButton, true);
        bc.bind("createAerosolProduct", processingParamGroup, createAerosolProductValueSet);

        bc.bind("useForwardView", useForwardViewCheckBox);
        bc.bind("computeCOT", computeCOTCheckBox);
        bc.bind("computeSF", computeSFCheckBox);
        bc.bind("computeSH", computeSHCheckBox);

        bc.bind("computeOcean", computeOceanCheckBox);
        bc.bind("computeLand", computeLandCheckBox);
        bc.bind("computeSurfaceReflectances", computeSurfaceReflectancesCheckBox);
        bc.bind("soilSpecName", soilSpecNameTextField);
        bc.bind("vegSpecName", vegSpecNameTextField);
        bc.bind("aveBlock", aveBlockTextField);

        Map<AbstractButton, Object> landAerosolModelGroupValueSet = new HashMap<AbstractButton, Object>(4);
        landAerosolModelGroupValueSet.put(defaultLandAerosolRadioButton, false);
        landAerosolModelGroupValueSet.put(customLandAerosolRadioButton, true);
        bc.bind("useCustomLandAerosol", landAerosolModelGroup, landAerosolModelGroupValueSet);
        bc.bind("customLandAerosol", landAerosolModelTextField);
    }


    private void initComponents() {
        setPreferredSize(new Dimension(600, 600));

        if (new File(SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT).exists()) {
            synergyAuxdataHome = SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT + File.separator +
                    "aerosolLUTs" + File.separator + "land";
        } else {
            // try this one (in case of calvalus processing)
            synergyAuxdataHome = SynergyConstants.SYNERGY_AUXDATA_CALVALUS_DEFAULT;
        }

        URL imgURL = getClass().getResource("images/Help24.gif");
        infoIcon = new ImageIcon(imgURL, "");

        processingParamGroup = new ButtonGroup();
        doPreprocessingRadioButton = new JRadioButton(SynergyConstants.preprocessingRadioButtonLabel);
        doCloudScreeningRadioButton = new JRadioButton(SynergyConstants.cloudScreeningRadioButtonLabel);
        doAerosolRetrievalRadioButton = new JRadioButton(SynergyConstants.aerosolRetrievalRadioButtonLabel);
        doAerosolRetrievalRadioButton.setSelected(true);
        processingParamGroup.add(doPreprocessingRadioButton);
        processingParamGroup.add(doCloudScreeningRadioButton);
        processingParamGroup.add(doAerosolRetrievalRadioButton);

        useForwardViewCheckBox = new JCheckBox(SynergyConstants.useForwardViewCheckboxLabel);
        computeCOTCheckBox = new JCheckBox(SynergyConstants.computeCOTCheckboxLabel);
        computeSFCheckBox = new JCheckBox(SynergyConstants.computeSFCheckboxLabel);
        computeSHCheckBox = new JCheckBox(SynergyConstants.computeSHCheckboxLabel);

        computeOceanCheckBox = new JCheckBox(SynergyConstants.computeOceanCheckboxLabel);
        computeOceanCheckBox.setSelected(true);
        computeLandCheckBox = new JCheckBox(SynergyConstants.computeLandCheckboxLabel);
        computeLandCheckBox.setSelected(true);
        computeSurfaceReflectancesCheckBox = new JCheckBox(SynergyConstants.computeSurfaceReflectancesCheckboxLabel);
        aveBlockTextField = new JFormattedTextField("7");
        aveBlockLabel = new JLabel("N x N average for AOD retrieval");

        soilSpecNameLabel = new JLabel(SynergyConstants.SOIL_SPEC_PARAM_LABEL + ":");

        final String soilSpecFileDefault = SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT + File.separator +
                SynergyConstants.SOIL_SPEC_PARAM_DEFAULT;
        this.synergyModel.setSoilSpecName(soilSpecFileDefault);
        soilSpecNameTextField = new JFormattedTextField(soilSpecFileDefault);
        soilSpecNameTextField.setEnabled(false);
        soilSpecFileChooserButton = new JButton(new SoilSpectrumFileChooserAction());
        soilSpecFileChooserButton.setPreferredSize(new Dimension(25, 20));
        soilSpecFileChooserButton.setMinimumSize(new Dimension(25, 20));

        vegSpecNameLabel = new JLabel(SynergyConstants.VEG_SPEC_PARAM_LABEL + ":");
        final String vegSpecFileDefault = SynergyConstants.SYNERGY_AUXDATA_HOME_DEFAULT + File.separator +
                SynergyConstants.VEG_SPEC_PARAM_DEFAULT;
        this.synergyModel.setVegSpecName(vegSpecFileDefault);
        vegSpecNameTextField = new JFormattedTextField(vegSpecFileDefault);
        vegSpecNameTextField.setEnabled(false);
        vegSpecFileChooserButton = new JButton(new VegSpectrumFileChooserAction());
        vegSpecFileChooserButton.setPreferredSize(new Dimension(25, 20));
        vegSpecFileChooserButton.setMinimumSize(new Dimension(25, 20));

        landAerosolModelLabel = new JLabel(SynergyConstants.LAND_AEROSOL_LABEL_TEXT + ":");
        landAerosolModelGroup = new ButtonGroup();
        defaultLandAerosolRadioButton = new JRadioButton(SynergyConstants.defaultLandAerosolButtonLabel);
        defaultLandAerosolRadioButton.setSelected(true);
        customLandAerosolRadioButton = new JRadioButton(SynergyConstants.customLandAerosolButtonLabel);
        customLandAerosolRadioButton.setSelected(false);
        landAerosolModelGroup.add(defaultLandAerosolRadioButton);
        landAerosolModelGroup.add(customLandAerosolRadioButton);
        landAerosolModelTextField = new JFormattedTextField(SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT);
        customLandAerosolLabel = new JLabel("List of land aerosol models: ");
    }

    public void addParameterDefaultPane(PropertyContainer propertyContainer, String title) {
        BindingContext context = new BindingContext(propertyContainer);

        PropertyPane parametersPane = new PropertyPane(context);
        JPanel paremetersPanel = parametersPane.createPanel();
        paremetersPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
        this.add(title, new JScrollPane(paremetersPanel));
    }

    public JPanel getParameterDefaultPane(PropertyContainer propertyContainer, String title) {
        BindingContext context = new BindingContext(propertyContainer);

        PropertyPane parametersPane = new PropertyPane(context);
        JPanel paremetersPanel = parametersPane.createPanel();
        return paremetersPanel;
    }


    public JPanel createProcessingParametersPanel() {

        TableLayout layout = new TableLayout(2);
        layout.setTableAnchor(TableLayout.Anchor.WEST);
        layout.setTableFill(TableLayout.Fill.HORIZONTAL);
        layout.setTablePadding(3, 3);
        final JPanel panel = new JPanel(layout);

        int rowIndex = 0;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellPadding(rowIndex, 0, new Insets(0, 24, 0, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(doPreprocessingRadioButton, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellPadding(rowIndex, 0, new Insets(0, 24, 0, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(doCloudScreeningRadioButton, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellPadding(rowIndex, 0, new Insets(0, 24, 0, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(doAerosolRetrievalRadioButton, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        ActionListener processingActionListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                updateProcessingUIstate();
            }
        };
        doPreprocessingRadioButton.addActionListener(processingActionListener);
        doCloudScreeningRadioButton.addActionListener(processingActionListener);
        doAerosolRetrievalRadioButton.addActionListener(processingActionListener);

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        layout.setCellWeightY(rowIndex, 0, 1.0);
        panel.setBorder(BorderFactory.createTitledBorder("Target Product Specification"));
        panel.add(new JPanel());

        return panel;
    }

    public JPanel createCloudScreeningParametersPanel() {

        TableLayout layout = new TableLayout(2);
        layout.setTableAnchor(TableLayout.Anchor.WEST);
        layout.setTableFill(TableLayout.Fill.HORIZONTAL);
        layout.setTablePadding(3, 3);
        final JPanel panel = new JPanel(layout);

        int rowIndex = 0;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(useForwardViewCheckBox, new TableLayout.Cell(rowIndex, 0));
        useForwardViewCheckBox.setSelected(true);
        rowIndex++;

        computeCOTCheckBox.setSelected(true);
        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(computeCOTCheckBox, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(computeSFCheckBox, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(computeSHCheckBox, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        layout.setCellWeightY(rowIndex, 0, 1.0);
        panel.add(new JPanel());

        return panel;
    }

    public JPanel createAerosolParametersPanel() {

        TableLayout layout = new TableLayout(2);
        layout.setTableAnchor(TableLayout.Anchor.WEST);
        layout.setTableFill(TableLayout.Fill.HORIZONTAL);
        layout.setTablePadding(10, 15);     // space between columns/rows
        final JPanel panel = new JPanel(layout);

        int rowIndex = 0;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(computeOceanCheckBox, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(computeLandCheckBox, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

//        layout.setCellColspan(rowIndex, 0, 2);
//        layout.setCellWeightX(rowIndex, 0, 1.0);
//        panel.add(computeSurfaceReflectancesCheckBox, new TableLayout.Cell(rowIndex, 0));
//        rowIndex++;


        layout.setCellWeightX(rowIndex, 0, 1.0);
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        panel.add(computeSurfaceReflectancesCheckBox, new TableLayout.Cell(rowIndex, 0));

        final MouseAdapter atmCorrInfoAdapter = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                String msg = SynergyConstants.ATM_CORR_INFO_MESSAGE;
                JOptionPane.showOptionDialog(null, msg, "MERIS/(A)ATSR Synergy - Info Message", JOptionPane.DEFAULT_OPTION,
                                             JOptionPane.INFORMATION_MESSAGE, null, null, null);
            }

        };

        JLabel atmCorrInfoLabel= new JLabel();
        atmCorrInfoLabel.setIcon(infoIcon);
        panel.add(atmCorrInfoLabel, new TableLayout.Cell(rowIndex, 1));
        atmCorrInfoLabel.addMouseListener(atmCorrInfoAdapter);
        rowIndex++;




        layout.setCellPadding(rowIndex, 0, new Insets(0, 12, 40, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 36, 40, 0));

        layout.setCellWeightX(rowIndex, 0, 0.1);
        panel.add(aveBlockLabel, new TableLayout.Cell(rowIndex, 0));
        layout.setCellWeightX(rowIndex, 1, 0.1);
        panel.add(aveBlockTextField, new TableLayout.Cell(rowIndex, 1));
        rowIndex++;

        final MouseAdapter auxdataInfoAdapter = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                String msg = SynergyConstants.AUXDATA_INFO_MESSAGE;
                JOptionPane.showOptionDialog(null, msg, "MERIS/(A)ATSR Synergy - Info Message", JOptionPane.DEFAULT_OPTION,
                                             JOptionPane.INFORMATION_MESSAGE, null, null, null);
            }

        };

        rowIndex = addSoilSpectrumParameters(layout, panel, rowIndex, auxdataInfoAdapter);
        rowIndex = addVegetationSpectrumParameters(layout, panel, rowIndex, auxdataInfoAdapter);
        rowIndex = addLandAerosolParameters(layout, panel, rowIndex);

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        layout.setCellWeightY(rowIndex, 0, 1.0);
        panel.add(new JPanel());

        return panel;
    }

    private int addSoilSpectrumParameters(TableLayout layout, JPanel panel, int rowIndex, MouseAdapter auxdataInfoAdapter) {
        JLabel soilSpecInfoLabel= new JLabel();
        soilSpecInfoLabel.setIcon(infoIcon);


        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        layout.setCellWeightX(rowIndex, 0, 0.1);
        panel.add(soilSpecNameLabel, new TableLayout.Cell(rowIndex, 0));
        layout.setCellWeightX(rowIndex, 1, 0.1);
        panel.add(soilSpecInfoLabel, new TableLayout.Cell(rowIndex, 1));
        soilSpecInfoLabel.addMouseListener(auxdataInfoAdapter);
        rowIndex++;

        layout.setCellPadding(rowIndex, 0, new Insets(0, 24, 20, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 20, 0));
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(soilSpecNameTextField, new TableLayout.Cell(rowIndex, 0));
        layout.setCellWeightX(rowIndex, 1, 0.0);
        panel.add(soilSpecFileChooserButton, new TableLayout.Cell(rowIndex, 1));
        rowIndex++;
        return rowIndex;
    }

    private int addVegetationSpectrumParameters(TableLayout layout, JPanel panel, int rowIndex, MouseAdapter auxdataInfoAdapter) {
        JLabel vegSpecInfoLabel= new JLabel();
        vegSpecInfoLabel.setIcon(infoIcon);


        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        layout.setCellWeightX(rowIndex, 0, 0.1);
        panel.add(vegSpecNameLabel, new TableLayout.Cell(rowIndex, 0));
        layout.setCellWeightX(rowIndex, 1, 0.1);
        panel.add(vegSpecInfoLabel, new TableLayout.Cell(rowIndex, 1));
        vegSpecInfoLabel.addMouseListener(auxdataInfoAdapter);
        rowIndex++;

        layout.setCellPadding(rowIndex, 0, new Insets(0, 24, 40, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 40, 0));
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(vegSpecNameTextField, new TableLayout.Cell(rowIndex, 0));
        layout.setCellWeightX(rowIndex, 1, 0.0);
        panel.add(vegSpecFileChooserButton, new TableLayout.Cell(rowIndex, 1));
        rowIndex++;
        return rowIndex;
    }

    private int addLandAerosolParameters(TableLayout layout, JPanel panel, int rowIndex) {
        JLabel landAerosolInfoLabel= new JLabel();
        landAerosolInfoLabel.setIcon(infoIcon);

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(landAerosolModelLabel, new TableLayout.Cell(rowIndex, 0));
        rowIndex++;

        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellPadding(rowIndex, 0, new Insets(0, 24, 0, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(defaultLandAerosolRadioButton, new TableLayout.Cell(rowIndex, 0));

        rowIndex++;

        layout.setCellPadding(rowIndex, 0, new Insets(0, 24, 0, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 24, 0, 0));
        layout.setCellWeightX(rowIndex, 0, 1.0);
        panel.add(customLandAerosolRadioButton, new TableLayout.Cell(rowIndex, 0));

        final MouseAdapter aerosolModelInfoAdapter = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                String msg = SynergyConstants.AEROSOL_MODEL_INFO_MESSAGE;
                JOptionPane.showOptionDialog(null, msg, "MERIS/(A)ATSR Synergy - Info Message", JOptionPane.DEFAULT_OPTION,
                                             JOptionPane.INFORMATION_MESSAGE, null, null, null);
            }

        };


        panel.add(landAerosolInfoLabel, new TableLayout.Cell(rowIndex, 1));
        landAerosolInfoLabel.addMouseListener(aerosolModelInfoAdapter);
        rowIndex++;

        layout.setCellWeightX(rowIndex, 0, 0.5);
        layout.setCellPadding(rowIndex, 0, new Insets(0, 48, 0, 0));
        layout.setCellPadding(rowIndex, 1, new Insets(0, 0, 0, 0));
        panel.add(customLandAerosolLabel, new TableLayout.Cell(rowIndex, 0));
        layout.setCellWeightX(rowIndex, 1, 0.5);
        panel.add(landAerosolModelTextField, new TableLayout.Cell(rowIndex, 1));
        rowIndex++;

        ActionListener landAerosolActionListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                updateLandAerosolModelUIstate();
            }
        };
        defaultLandAerosolRadioButton.addActionListener(landAerosolActionListener);
        customLandAerosolRadioButton.addActionListener(landAerosolActionListener);
        return rowIndex;
    }

    private void updateProcessingUIstate() {
    }

    private void updateLandAerosolModelUIstate() {
        customLandAerosolSelected = customLandAerosolRadioButton.isSelected();
        customLandAerosolLabel.setEnabled(customLandAerosolSelected);
        landAerosolModelTextField.setEnabled(customLandAerosolSelected);
        if (!customLandAerosolSelected) {
            landAerosolModelTextField.setText(SynergyConstants.AEROSOL_MODEL_PARAM_DEFAULT);
        }
    }



    private void updateUIState() {
        soilSpecNameTextField.setText(synergyModel.getSoilSpecName());
        vegSpecNameTextField.setText(synergyModel.getVegSpecName());
        updateProcessingUIstate();
        updateLandAerosolModelUIstate();
    }

    public void setEnabled(boolean enabled) {
    }

    private class SoilSpectrumFileChooserAction extends AbstractAction {

        private static final String APPROVE_BUTTON_TEXT = "Select";

        public SoilSpectrumFileChooserAction() {
            super("...");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            Window windowAncestor = null;
            if (event.getSource() instanceof JComponent) {
                JButton button = (JButton) event.getSource();
                if (button != null) {
                    windowAncestor = SwingUtilities.getWindowAncestor(button);
                }
            }
            final String currentFile = synergyModel.getSoilSpecName();
            final JFileChooser chooser = FileChooserFactory.getInstance().createFileChooser(new File(currentFile));
            chooser.setDialogTitle("Select File");
            if (chooser.showDialog(windowAncestor, APPROVE_BUTTON_TEXT) == JFileChooser.APPROVE_OPTION) {
                final File selectedFile = chooser.getSelectedFile();
                if (selectedFile != null) {
                    synergyModel.setSoilSpecName(selectedFile.getAbsolutePath());
                } else {
                    synergyModel.setSoilSpecName(".");
                }
                updateUIState();
            }
        }
    }

    private class VegSpectrumFileChooserAction extends AbstractAction {

        private static final String APPROVE_BUTTON_TEXT = "Select";

        public VegSpectrumFileChooserAction() {
            super("...");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            Window windowAncestor = null;
            if (event.getSource() instanceof JComponent) {
                JButton button = (JButton) event.getSource();
                if (button != null) {
                    windowAncestor = SwingUtilities.getWindowAncestor(button);
                }
            }
            final String currentFile = synergyModel.getVegSpecName();
            final JFileChooser chooser = FileChooserFactory.getInstance().createFileChooser(new File(currentFile));
            chooser.setDialogTitle("Select File");
            if (chooser.showDialog(windowAncestor, APPROVE_BUTTON_TEXT) == JFileChooser.APPROVE_OPTION) {
                final File selectedFile = chooser.getSelectedFile();
                if (selectedFile != null) {
                    synergyModel.setVegSpecName(selectedFile.getAbsolutePath());
                } else {
                    synergyModel.setVegSpecName(".");
                }
                updateUIState();
            }
        }

    }
}
