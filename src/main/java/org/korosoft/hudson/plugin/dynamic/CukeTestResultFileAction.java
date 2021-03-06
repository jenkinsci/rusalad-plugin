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
import org.apache.commons.lang.StringEscapeUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.korosoft.hudson.plugin.model.RuSaladDynamicAction;
import org.korosoft.hudson.plugin.model.RuSaladDynamicActionContext;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

/**
 * Russian Salad Cucumber test result file server action
 *
 * @author Dmitry Korotkov
 * @since 1.0
 */
public class CukeTestResultFileAction implements RuSaladDynamicAction {
    public static final String CUKE_RESULT = "cukeResult";

    public void doApply(RuSaladDynamicActionContext context, FilePath reportPath) {
        if (context.getBuild() == null || reportPath == null) {
            return;
        }
        saveReportFiles(context.getBuild().getRootDir(), reportPath);
    }

    public String getUrlName() {
        return "Files";
    }

    public void doDynamic(RuSaladDynamicActionContext context, StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        String path = req.getRestOfPath().substring(getUrlName().length() + 1);
        path = path.replaceAll("[/\\\\]\\.\\.[/\\\\]", "/").replaceAll("^[\\\\/]*", "");

        FilePath reportPath = new FilePath(context.getBuild().getRootDir()).child(CUKE_RESULT);
        FilePath serveFile = reportPath.child(path);
        try {
            if (!serveFile.exists() && path.endsWith(".xml")) {
                FilePath srtFile = reportPath.child(path.substring(0, path.length() - 4) + ".srt");
                if (srtFile.exists()) {
                    serveSrtAsTimeText(rsp, srtFile);
                }
            }
            if (!serveFile.exists()) {
                rsp.sendError(HttpServletResponse.SC_NOT_FOUND, "File " + req.getRestOfPath() + " not found");
            }
            if (serveFile.isDirectory()) {
                rsp.sendError(HttpServletResponse.SC_FORBIDDEN, "Directory browsing is not enabled");
            }
            rsp.serveFile(req, serveFile.read(), serveFile.lastModified(), serveFile.length(), serveFile.getName());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void serveSrtAsTimeText(StaplerResponse rsp, FilePath srtFile) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.append("<tt xml:lang=\"en\" xmlns=\"http://www.w3.org/2006/10/ttaf1\" xmlns:tts=\"http://www.w3.org/2006/10/ttaf1#styling\">");
        builder.append("<body>");
        builder.append("<div xml:lang=\"en\">");
        BufferedReader reader = new BufferedReader(new InputStreamReader(srtFile.read()));
        for (String l = reader.readLine(); l != null; l = reader.readLine()) {
            if (l.length() == 0) {
                if (reader.readLine() == null) {
                    break;
                }
            }
            String timing = reader.readLine();
            if (timing == null) {
                break;
            }
            String text = reader.readLine();
            if (text == null) {
                break;
            }
            String[] times = timing.split(" --> ");
            if (times.length != 2) {
                continue;
            }
            String[] start_ms = times[0].split(",");
            String[] end_ms = times[1].split(",");
            if (start_ms.length != 2 || end_ms.length != 2) {
                continue;
            }
            String[] start_s = start_ms[0].split(":");
            String[] end_s = end_ms[0].split(":");
            if (start_s.length != 3 || end_s.length != 3) {
                continue;
            }
            text = StringEscapeUtils.escapeHtml(text);
            long start, end;
            try {
                start = Long.parseLong(start_ms[1]) + 1000 * Long.parseLong(start_s[2]) + 60000 * Long.parseLong(start_s[1]) + 3600000 * Long.parseLong(start_s[0]);
                end = Long.parseLong(end_ms[1]) + 1000 * Long.parseLong(end_s[2]) + 60000 * Long.parseLong(end_s[1]) + 3600000 * Long.parseLong(end_s[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            String v1 = Integer.toString(Integer.parseInt(start_ms[1]) / 10);
            if (v1.length() == 1) {
                v1 = "0" + v1;
            }
            String v2 = Long.toString((end - start) / 1000);
            String v3 = Long.toString(((end - start) % 1000) / 10);
            if (v3.length() == 1) {
                v3 = "0" + v3;
            }
            text = text.replaceAll("color=(#[0-9a-fA-F]+)", "color=\"$1\"");
            builder.append(String.format("<p begin=\"%s:%s:%s.%s\" dur=\"%s.%s\">%s</p>", start_s[0], start_s[1], start_s[2], v1, v2, v3, text));

        }
        builder.append("</div></body></tt>");
        reader.close();

        rsp.setContentType("text/xml");
        rsp.setCharacterEncoding("UTF-8");
        PrintWriter writer = rsp.getWriter();
        writer.print(builder.toString());
        writer.close();
    }

    private void saveReportFiles(File rootDir, FilePath reportFolder) {
        final File cukeResult = new File(rootDir, CUKE_RESULT);
        if (!cukeResult.mkdir()) {
            throw new RuntimeException("Failed to create folder " + cukeResult);
        }
        try {
            reportFolder.copyRecursiveTo(new FilePath(cukeResult));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
