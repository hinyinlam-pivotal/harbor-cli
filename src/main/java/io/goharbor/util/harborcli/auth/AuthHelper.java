/*
 * CLI for Harbor
 * Copyright 2021 VMware, Inc.
 *
 * This product is licensed to you under the Apache 2.0 license (the "License").  You may not use this product except in compliance with the Apache 2.0 License.
 *
 * This product may include a number of subcomponents with separate copyright notices and license terms. Your use of these subcomponents is subject to the terms and conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package io.goharbor.util.harborcli.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.goharbor.client.openapi.ApiClient;
import io.goharbor.client.openapi.ApiException;
//import io.goharbor.client.openapi.apis.ProjectApi;
//import io.goharbor.client.openapi.models.Project;
import io.goharbor.util.harborcli.CommandDispatcherService;
import io.goharbor.util.harborcli.OpenAPIParserService;
import io.goharbor.util.harborcli.config.BasicAuth;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

@Service
public class AuthHelper {

    Logger logger = LoggerFactory.getLogger(AuthHelper.class);

    @Getter
    private ApiClient apiClient;

    @Autowired
    private OpenAPIParserService apiParserService;

    @Autowired
    private CommandDispatcherService cmdDispatcherSvc;

    public AuthHelper(){
        apiClient = loadBasicAuthentication(); //load existing API Client
    }

    private ApiClient loadBasicAuthentication(){
        ApiClient client = new ApiClient();
        ObjectMapper mapper = new ObjectMapper();
        File harborAuthConfigFile = null;
        try {
            harborAuthConfigFile = getHarborAuthConfig();
            if(harborAuthConfigFile.length()==0l){
                logger.debug("Harbor auth config file is empty");
                return null;
            }
            BasicAuth basicAuth = mapper.readValue(harborAuthConfigFile,BasicAuth.class);
            client.setUsername(basicAuth.username);
            client.setPassword(basicAuth.password);
            client.setBasePath(basicAuth.api);
            return client;
        } catch (IOException exception) {
            exception.printStackTrace();
        }
        return null;
    }

    private String correctApiURL(String api) throws MalformedURLException {
        //Assume the user input "harbor.abc.com" without full base URL - should be: https://harbor.abc.com/api/v2.0
        if(!api.contains("https://")){
            api = "https://" + api;
        }
        URL harborApiURL = new URL(api);
        if(!harborApiURL.getPath().equals("/api/v2.0")){
            api = api + "/api/v2.0";
        }
        URL changedURL = new URL(api); // should throw exception if our "fixes" on URL is invalid
        return api;
    }

    public void logout(){
        deleteHarborAuthConfig();
        logger.debug("Logout by deleting locally saved credential");
    }

    public int login(CommandLine.ParseResult loginCommand){
        //Set<OptionSpec> options = loginCommand.matchedOptionsSet();
        String username = loginCommand.matchedOption("username").getValue();
        String password = loginCommand.matchedOption("password").getValue();
        String api = loginCommand.matchedOption("api").getValue();
        //Check API URL correctness
        try {
            api = correctApiURL(api);
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return -1;
        }

        System.out.println("Try logging in using\nUsername:" + username + "\nPassword: <hidden> " + "\napi: "+ api);
        //Make an API request to valid if the credentials are correct
        try {
            tryLogin(username, password, api);
            return 0;
        }catch (ApiException exception){
            exception.printStackTrace();
        }
        return -1;
    }


    private boolean validateLoginInfo(ApiClient tempApiClient){
        try { //See if the API for trying login exists or not
            Object apiClass = cmdDispatcherSvc.getApiClassInstanceWithAPIClient("ProjectApi", false, tempApiClient);
            Method method = apiParserService.getMethodByAPIAndMethodName("ProjectApi", "listProjects");
            if (method != null && apiClass != null) {
                Parameter[] parameters = method.getParameters();
                Object[] emptyApicall = new Object[parameters.length];
                method.invoke(apiClass, emptyApicall);
                return true;
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        //Trying legacy API endpoint
        try { //See if the API for trying login exists or not
            Object apiClass = cmdDispatcherSvc.getApiClassInstanceWithAPIClient("ProductsApi", false, tempApiClient);
            Method method = apiParserService.getMethodByAPIAndMethodName("ProductsApi", "usersSearchGet");
            if (method != null && apiClass != null) {
                Parameter[] parameters = method.getParameters();
                Object[] emptyApicall = new Object[parameters.length];
                emptyApicall[0]="unrealusername";
                method.invoke(apiClass, emptyApicall);
                return true;
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void tryLogin(String username, String password, String api) throws ApiException {
        ApiClient tryLoginClient = new ApiClient();
        tryLoginClient.setUsername(username);
        tryLoginClient.setPassword(password);
        tryLoginClient.setBasePath(api);

        boolean success = validateLoginInfo(tryLoginClient);
        if(!success){
            System.out.println("Login information incorrect");
            System.exit(-1);
        }

//        ProjectApi projectApi = new ProjectApi(tryLoginClient);
//        List<Project> projects = projectApi.listProjects(null,1l,1l,null,null,null);
        //At this point, no exception has thrown, thus login is success
        System.out.println("Login success");
        saveBasicAuthentication(username, password,api);
        //refresh this API client(may not be useful as this app is going to exit) for any success verified and saved credential
        this.apiClient = loadBasicAuthentication();
    }

    private File harborConfigDir = new File(System.getProperty("user.home") + File.separator + ".harbor");

    private void saveBasicAuthentication(String username, String password, String api){
        BasicAuth basicAuth = new BasicAuth();
        basicAuth.setUsername(username);
        basicAuth.setPassword(password);
        basicAuth.setApi(api);
        ObjectMapper mapper = new ObjectMapper();
        try {
            String value = mapper.writeValueAsString(basicAuth);

            File basicAuthConfig = getHarborAuthConfig();

            try(FileWriter writer = new FileWriter(basicAuthConfig)){
                writer.write(value);
            }
            System.out.println("Credential Saved to " + basicAuthConfig);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteHarborAuthConfig(){
        File basicAuthConfig = new File(harborConfigDir,"basicAuth.json");
        if(basicAuthConfig.exists()){
            basicAuthConfig.delete();
        }
    }

    private File getHarborAuthConfig() throws IOException {
        if(!harborConfigDir.exists()){
            harborConfigDir.mkdir();
        }

        File basicAuthConfig = new File(harborConfigDir,"basicAuth.json");
        if(!basicAuthConfig.exists()){
            basicAuthConfig.createNewFile();
            return basicAuthConfig;
        }
        return basicAuthConfig;
    }

}
