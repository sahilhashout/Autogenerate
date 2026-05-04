package com.mysite.core.services;

import org.apache.sling.api.resource.ResourceResolver;

public interface GoogleDocPageCreationService {

    String createPage(ResourceResolver resolver, String fileId) throws Exception;
}
