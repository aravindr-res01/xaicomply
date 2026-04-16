package com.xaicomply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Binds all xai.* configuration properties.
 */
@Configuration
@ConfigurationProperties(prefix = "xai")
public class ModelConfig {

    private ShapConfig shap = new ShapConfig();
    private LimeConfig lime = new LimeConfig();
    private RiskWeightsConfig riskWeights = new RiskWeightsConfig();
    private PreprocessingConfig preprocessing = new PreprocessingConfig();
    private ReportingConfig reporting = new ReportingConfig();
    private ModelPathConfig model = new ModelPathConfig();

    public static class ShapConfig {
        private String backgroundMeans = "0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0";
        private int cacheSize = 1000;

        public float[] getBackgroundMeansArray() {
            String[] parts = backgroundMeans.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        }

        public String getBackgroundMeans() { return backgroundMeans; }
        public void setBackgroundMeans(String backgroundMeans) { this.backgroundMeans = backgroundMeans; }
        public int getCacheSize() { return cacheSize; }
        public void setCacheSize(int cacheSize) { this.cacheSize = cacheSize; }
    }

    public static class LimeConfig {
        private int numSamples = 500;
        private double sigmaFactor = 0.75;

        public int getNumSamples() { return numSamples; }
        public void setNumSamples(int numSamples) { this.numSamples = numSamples; }
        public double getSigmaFactor() { return sigmaFactor; }
        public void setSigmaFactor(double sigmaFactor) { this.sigmaFactor = sigmaFactor; }
    }

    public static class RiskWeightsConfig {
        private String weights = "0.10,0.10,0.05,0.05,0.05,0.05,0.05,0.05,0.05,0.05,0.05,0.05,0.05,0.05,0.05,0.02,0.02,0.02,0.02,0.08";
        private double threshold = 0.65;

        public float[] getWeightsArray() {
            String[] parts = weights.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        }

        public String getWeights() { return weights; }
        public void setWeights(String weights) { this.weights = weights; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
    }

    public static class PreprocessingConfig {
        private String mccCategories = "5411,5912,5812,4111,5541,5999,7011,5311,5961,0000";
        private String countryCodes = "US,GB,DE,FR,CN,OTHER";

        public List<String> getMccCategoriesList() {
            return Arrays.asList(mccCategories.split(","));
        }

        public List<String> getCountryCodesList() {
            return Arrays.asList(countryCodes.split(","));
        }

        public String getMccCategories() { return mccCategories; }
        public void setMccCategories(String mccCategories) { this.mccCategories = mccCategories; }
        public String getCountryCodes() { return countryCodes; }
        public void setCountryCodes(String countryCodes) { this.countryCodes = countryCodes; }
    }

    public static class ReportingConfig {
        private String outputDir = "./reports";
        private int flagsTriggerCount = 10;

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
        public int getFlagsTriggerCount() { return flagsTriggerCount; }
        public void setFlagsTriggerCount(int flagsTriggerCount) { this.flagsTriggerCount = flagsTriggerCount; }
    }

    public static class ModelPathConfig {
        private String path = "classpath:model.onnx";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public ShapConfig getShap() { return shap; }
    public void setShap(ShapConfig shap) { this.shap = shap; }

    public LimeConfig getLime() { return lime; }
    public void setLime(LimeConfig lime) { this.lime = lime; }

    public RiskWeightsConfig getRiskWeights() { return riskWeights; }
    public void setRiskWeights(RiskWeightsConfig riskWeights) { this.riskWeights = riskWeights; }

    public PreprocessingConfig getPreprocessing() { return preprocessing; }
    public void setPreprocessing(PreprocessingConfig preprocessing) { this.preprocessing = preprocessing; }

    public ReportingConfig getReporting() { return reporting; }
    public void setReporting(ReportingConfig reporting) { this.reporting = reporting; }

    public ModelPathConfig getModel() { return model; }
    public void setModel(ModelPathConfig model) { this.model = model; }
}
