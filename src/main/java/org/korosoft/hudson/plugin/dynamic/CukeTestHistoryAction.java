package org.korosoft.hudson.plugin.dynamic;

/*

The New BSD License

Copyright (c) 2011-2013, Dmitry Korotkov
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright notice, this
  list of conditions and the following disclaimer in the documentation and/or
  other materials provided with the distribution.

- Neither the name of the Jenkins RuSalad Plugin nor the names of its
  contributors may be used to endorse or promote products derived from this
  software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 */

import hudson.FilePath;
import hudson.model.AbstractBuild;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.korosoft.hudson.plugin.RuSaladPublisher;
import org.korosoft.hudson.plugin.model.CukeFeature;
import org.korosoft.hudson.plugin.model.CukeTestResult;
import org.korosoft.hudson.plugin.model.RuSaladDynamicAction;
import org.korosoft.hudson.plugin.model.RuSaladDynamicActionContext;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Russian Salad Cucumber test result history action
 *
 * @author Dmitry Korotkov
 * @since 1.0
 */
public class CukeTestHistoryAction implements RuSaladDynamicAction {

    public String getUrlName() {
        return "History";
    }

    public void doDynamic(RuSaladDynamicActionContext context, StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        rsp.setContentType("application/json");
        rsp.setCharacterEncoding("UTF-8");
        String res = loadHistory(context.getBuild()).toString(4);
        PrintWriter writer = rsp.getWriter();
        writer.write(res);
        writer.close();
    }

    public void doApply(RuSaladDynamicActionContext context, FilePath reportPath) {
    }

    private JSONObject loadHistory(AbstractBuild<?, ?> build) throws IOException {
        JSONObject result = new JSONObject();
        int numberOfBuildsToProcess = build.getProject().getPublishersList().get(RuSaladPublisher.class).myGetDescriptor().getNumberOfHistoryBuildsInPopup();
        while (build != null && numberOfBuildsToProcess > 0) {
            try {
                processBuild(result, build);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            numberOfBuildsToProcess--;
            build = build.getPreviousBuild();
        }
        return result;
    }

    private void processBuild(JSONObject result, AbstractBuild<?, ?> build) throws IOException, InterruptedException {

        if (!result.has("featureNames")) {
            result.put("featureNames", new JSONArray());
        }
        if (!result.has("features")) {
            result.put("features", new JSONObject());
        }
        if (!result.has("builds")) {
            result.put("builds", new JSONArray());
        }
        result.getJSONArray("builds").add(0, build.getNumber());

        CukeTestResult cukeTestResult;
        try {
            cukeTestResult = new CukeTestResult(new FilePath(build.getRootDir()).child(CukeTestResultFileAction.CUKE_RESULT));
        } catch (IOException e) {
            return;
        }

        JSONObject featuresObject = result.getJSONObject("features");
        JSONArray featureNames = result.getJSONArray("featureNames");
        int lastIdx = 0;
        for (String featureName : cukeTestResult.getFeatureNames()) {
            int idx = featureNames.indexOf(featureName);
            if (idx < 0) {
                featureNames.add(lastIdx, featureName);
                JSONObject feature = new JSONObject();
                feature.put("scenarioNames", new JSONArray());
                feature.put("scenarios", new JSONObject());
                featuresObject.put(featureName, feature);
                lastIdx++;
            } else {
                lastIdx = idx;
            }

            processFeatureResult(result, build, featureName, cukeTestResult.getFeatures().get(featureName));
        }
    }

    private void processFeatureResult(JSONObject result, AbstractBuild<?, ?> build, String featureName, CukeFeature feature) {
        JSONArray scenarioNames = result.getJSONObject("features").getJSONObject(featureName).getJSONArray("scenarioNames");
        JSONObject scenarioResults = result.getJSONObject("features").getJSONObject(featureName).getJSONObject("scenarios");

        int lastIdx = 0;
        for (Object s : feature.getReport().getJSONArray("scenarios")) {
            if (!(s instanceof JSONObject)) {
                continue;
            }
            JSONObject scenario = (JSONObject) s;
            String scenarioName = scenario.getString("scenarioName");
            int idx = scenarioNames.indexOf(scenarioName);
            if (idx == -1) {
                scenarioNames.add(lastIdx, scenarioName);
                scenarioResults.put(scenarioName, new JSONObject());
                lastIdx++;
            } else {
                lastIdx = idx;
            }
            JSONObject scenarioResultsObject = scenarioResults.getJSONObject(scenarioName);
            String status;
            if (scenario.has("status")) {
                status = scenario.getString("status");
            } else {
                status = scenario.getBoolean("passed") ? "passed" : "failed";
            }
            scenarioResultsObject.put(Integer.toString(build.getNumber()), status);
        }
    }
}
