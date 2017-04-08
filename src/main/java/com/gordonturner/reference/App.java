package com.gordonturner.reference;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.apache.http.HttpEntity;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;

/**
 * The jumpbox should be reachable via:
 *
 * ssh -i reference.pem ubuntu@JUMP_BOX
 *
 * And then from the JUMP_BOX the REMOTE_SERVER should be reachable via:
 *
 * ssh -i reference.pem ubuntu@REMOTE_SERVER
 *
 * Where the `reference.pem` is the ssh key file for each.
 *
 */
public class App {
  
  static final Logger logger = Logger.getLogger(App.class);
  
  private static final String STRICT_HOST_KEY_CHECKING_KEY = "StrictHostKeyChecking";
  private static final String STRICT_HOST_KEY_CHECKING_VALUE = "no";
  private static final String CHANNEL_TYPE = "shell";
  
  public static void main(String[] args) throws IOException, AuthenticationException, JSchException
  {
    
    logger.debug("Called");
    
    // TODO: Catch exceptions and close any open connections, don't leave them open!
    
    // TODO: Load properties from configuration file
    
    
    /*
     * BEGIN required customization
     */
    
    // Properties for tunnel and server
    String sshHost1 = "JUMP_BOX";
    String sshUser1 = "ubuntu";
    String sshHost2 = "REMOTE_SERVER";
    String sshuser2 = "ubuntu";

    // NOTE: Shared key file between sshHost1 and sshHost2, common for providers like AWS.
    String sshKeyFile = "ssh-key.pem";
    
    // Properties for Tomcat
    String tomcatUsername = "admin";
    String tomcatPassword = "SEEKRIT";
    int tomcatPort = 8080;
    
    // NOTE: hard coding `example-web` path to match `/tmp/example-web.war` file
    
    String tomcatUploadUrl = "http://localhost:8080/manager/text/deploy?path=/example-web&update=true";
    String tomcatUploadFile = "/tmp/example-web.war";
    
    /*
     * END required customization
     */
    
     
    Session session = null;
    Session[] sessions = new Session[2];
    
    // Create JSch object and set pem key
    JSch jsch = new JSch();
    jsch.addIdentity(sshKeyFile);
    jsch.setConfig(STRICT_HOST_KEY_CHECKING_KEY, STRICT_HOST_KEY_CHECKING_VALUE);
    
    // Open first session
    logger.info("Attempting connection to " + sshUser1 + "@" + sshHost1);
    sessions[0] = session = jsch.getSession(sshUser1, sshHost1, 22);
    session.connect();
    logger.info("Connected to " + sshUser1 + "@" + sshHost1);
    
    // Set port forwarding hop 1
    logger.info("Attempting to start port forwarding");
    int assignedPort = session.setPortForwardingL(0, sshHost2, 22);
    logger.info("Completed port forwarding");
    
    // Open second session
    logger.info("Attempting connection to " + sshuser2 + "@" + sshHost2);
    
    sessions[1] = session = jsch.getSession(sshuser2, "127.0.0.1", assignedPort);
    session.setHostKeyAlias(sshHost2);
    session.connect();
    logger.info("Connected to " + sshuser2 + "@" + sshHost2);
    
    // Set port forwarding hop 2
    logger.info("Attempting to start port forwarding");
    int assignedPort2 = session.setPortForwardingL(tomcatPort, "127.0.0.1", tomcatPort);
    logger.info("Completed port forwarding  localhost: " + tomcatPort + " -> 127.0.0.1:" + tomcatPort );
    
    Channel channel = session.openChannel(CHANNEL_TYPE);
    channel.connect();

    // For Tomcat 7, running the manager webapp.
    //
    // Command line call is:
    //
    // curl -X PUT \
    // --user root:password \
    // -T "example-web.war" \
    // "http://manager:manager@localhost:8080/manager/text/deploy?path=/example-web&update=true"
  
    logger.debug("Attempting to upload file: " + tomcatUploadFile);
    logger.debug("Attempting to upload to:   " + tomcatUploadUrl );
  
    CloseableHttpClient httpclient = HttpClients.createDefault();
    HttpPut httpPut = new HttpPut(tomcatUploadUrl);
    MultipartEntityBuilder builder = MultipartEntityBuilder.create();
    UsernamePasswordCredentials credentials= new UsernamePasswordCredentials(tomcatUsername, tomcatPassword);
    httpPut.addHeader(new BasicScheme().authenticate(credentials, httpPut, null));
    File file = new File( tomcatUploadFile );
    builder.addBinaryBody( "file", file, ContentType.APPLICATION_OCTET_STREAM, tomcatUploadFile );
    HttpEntity multipart = builder.build();
    httpPut.setEntity(multipart);
    
    // Execute http post request
    CloseableHttpResponse response = httpclient.execute(httpPut);
    
    // Evaluate response
    logger.debug("Request status:" + response.getStatusLine());
    if( response.getStatusLine().getStatusCode() != 200 )
    {
      response.close();
      httpclient.close();
    
      logger.error("Request was not 200 OK, ");
      logger.debug("Failed to upload file: " + tomcatUploadFile);
      logger.debug("Failed to upload to:   " + tomcatUploadUrl );
    }
    else
    {
      response.close();
      httpclient.close();
    
      logger.debug("Completed uploading file: " + tomcatUploadFile);
      logger.debug("Completed uploading to:   " + tomcatUploadUrl );
    }
    
    // Close tunnel
    logger.info("Closing tunnels");
    for (int i = sessions.length - 1; i >= 0; i--) {
      logger.info("Closing " + sessions[i].getUserName() + "@" + sessions[i].getHost());
      sessions[i].disconnect();
    }
    
    return;
  }
}
