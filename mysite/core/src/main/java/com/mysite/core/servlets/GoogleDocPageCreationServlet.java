package com.mysite.core.servlets;

import com.mysite.core.services.GoogleDocPageCreationService;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;

@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/shriram/create-page-service",
                "sling.servlet.methods=GET"
        }
)
public class GoogleDocPageCreationServlet extends SlingAllMethodsServlet {

    @Reference
    private GoogleDocPageCreationService googleDocPageCreationService;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");

        String fileId = request.getParameter("fileId");

        try {
            String destPath = googleDocPageCreationService.createPage(request.getResourceResolver(), fileId);
            response.getWriter().write("✅ Page created & updated: " + destPath);
        } catch (IllegalArgumentException e) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("❌ " + e.getMessage());
        } catch (Exception e) {
            response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("❌ Error: " + e.getMessage());
        }
    }
}
