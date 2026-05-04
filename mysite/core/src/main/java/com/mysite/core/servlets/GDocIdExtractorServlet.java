//package com.mysite.core.servlets;
//
//import org.apache.sling.api.SlingHttpServletRequest;
//import org.apache.sling.api.SlingHttpServletResponse;
//import org.apache.sling.api.servlets.SlingAllMethodsServlet;
//import org.osgi.service.component.annotations.Component;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import javax.servlet.Servlet;
//import javax.servlet.ServletException;
//import java.io.IOException;
//import java.io.PrintWriter;
//
//@Component(
//        service = Servlet.class,
//        property = {
//                "sling.servlet.paths=/bin/gdoc-extractor/ids",
//                "sling.servlet.methods=GET"
//        }
//)
//public class GDocIdExtractorServlet extends SlingAllMethodsServlet {
//
//    private static final Logger LOG = LoggerFactory.getLogger(GDocIdExtractorServlet.class);
//
//    @Override
//    protected void doGet(SlingHttpServletRequest request,
//                         SlingHttpServletResponse response)
//            throws ServletException, IOException {
//
//        response.setContentType("application/json");
//        response.setCharacterEncoding("UTF-8");
//        response.setHeader("Access-Control-Allow-Origin", "*");
//
//        // Read fileIds from query params
//        // e.g. /bin/gdoc-extractor/ids?fileId=abc&fileId=xyz
//        String[] fileIds = request.getParameterValues("fileId");
//
//        if (fileIds == null || fileIds.length == 0) {
//            response.setStatus(400);
//            PrintWriter writer = response.getWriter();
//            writer.write("{\"success\":false,\"message\":\"No fileId param found\"}");
//            writer.flush();
//            writer.close();
//            return;
//        }
//
//        // Log each file ID
//        LOG.info("=== GDoc Extractor: {} file ID(s) ===", fileIds.length);
//        for (int i = 0; i < fileIds.length; i++) {
//            LOG.info("  File ID [{}] : {}", i + 1, fileIds[i]);
//        }
//
//        response.setStatus(200);
//        PrintWriter writer = response.getWriter();
//        writer.write("{\"success\":true,\"message\":\"" + fileIds.length + " file ID(s) logged\"}");
//        writer.flush();
//        writer.close();
//    }
//}