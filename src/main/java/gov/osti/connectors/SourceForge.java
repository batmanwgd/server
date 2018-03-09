
package gov.osti.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.osti.connectors.sourceforge.License;
import gov.osti.connectors.sourceforge.Person;
import gov.osti.connectors.sourceforge.Response;
import gov.osti.entity.DOECodeMetadata;
import gov.osti.entity.Developer;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metadata scraper for SourceForge public API projects.
 * 
 * @author nensor
 */
public class SourceForge implements ConnectorInterface {
    // base URL for SourceForge API requests
    private static final String SOURCEFORGE_API_BASEURL = "https://sourceforge.net/rest/p/";
    // the logger implementation
    private static final Logger log = LoggerFactory.getLogger(SourceForge.class);
    // pattern to match for PROJECT NAME
    private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile("/(?:p|projects)/([a-zA-Z0-9_-]+).*$");
    
    /**
     * initialize this Connector
     * @throws IOException on errors
     */
    @Override
    public void init() throws IOException {
        // not required
    }
    
    /**
     * Attempt to read the PROJECT NAME from the given URL.
     * 
     * Assumes it contains "sourceforge.net" and the PATH contains the
     * "project/project-name" value.
     * SF might also be of the form: sourceforge.net/p/project-name or sf.net/p/project-name
     * for example.
     * 
     * @param url the URL to process
     * @return the PROJECT NAME if possible, or null if not
     */
    protected static String getProjectNameFromUrl(String url) {
        try {
            String safeUrl = (null==url) ? "" : url.trim();
            // do not assume protocol, must be provided
            URI uri = new URI(safeUrl);
            
            // protection against bad URL input
            if (null!=uri.getHost()) {
                if (uri.getHost().contains("sourceforge.net") ||
                    uri.getHost().contains("sf.net")) {
                    // assume SourceForge path is formed by "/projects/project-name"
                    if (null==uri.getPath())
                        return null;
                    
                    Matcher m = PROJECT_NAME_PATTERN.matcher(uri.getPath());
                    return (m.find()) ? 
                            m.group(1) :
                            null;
                }
            }
        } catch ( URISyntaxException e ) {
            // warn that URL is not a valid URI
            log.warn("Not a valid URI: " + url + " message: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Obtain the relevant Connector-driven information on this named project
     * from SourceForge.
     * 
     * @param url the URL to read from
     * @return a JsonNode of the metadata, or null if not possible
     */
    @Override
    public JsonNode read(String url) {
        DOECodeMetadata md = new DOECodeMetadata();
        ObjectMapper mapper = new ObjectMapper();
        
        // attempt to identify the project name
        String name = getProjectNameFromUrl(url);
        if (null==name)
            return null;
        
         // acquire the SourceForge API response as JSON
         HttpGet get = new HttpGet(SOURCEFORGE_API_BASEURL + name);

         try {
             // Convert the JSON into an Object we can handle
             Response response = mapper.readValue(HttpUtil.fetch(get), Response.class);

             // parse the relevant response parts into Metadata
             md.setSoftwareTitle(response.getName());
             md.setAcronym(response.getShortname());
             md.setDescription(response.getShortDescription());

             License[] licenses = response.getCategories().getLicense();

             List<String> license_values = new ArrayList<>();
             for ( License license : licenses ) {
                 license_values.add(license.getFullname());
             }
             md.setLicenses(license_values);

             Person[] developers = response.getDevelopers();
             List<Developer> devs = new ArrayList<>();

             for ( Person developer : developers ) {
                 int space = developer.getName().indexOf(" ");
                 Developer dev = new Developer();
                 if ( -1==space ) {
                     dev.setFirstName(developer.getName());
                 } else {
                     dev.setFirstName(developer.getName().substring(0, space));
                     dev.setLastName(developer.getName().substring(space+1));
                 }
                 devs.add(dev);
             }
             md.setDevelopers(devs);
             return md.toJson();
         } catch ( IOException e ) {
             // here's where we warn log error messages
             log.warn("IO Error reading from SourceForge: " + e.getMessage());
             log.warn("Repository/project: " + name);
         }
         
         // could not process this URL
         return null;
    }
}
