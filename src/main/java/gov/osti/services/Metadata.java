package gov.osti.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import gov.osti.connectors.ConnectorFactory;
import gov.osti.connectors.BitBucket;
import gov.osti.connectors.GitHub;
import gov.osti.connectors.GitLab;
import gov.osti.connectors.HttpUtil;
import gov.osti.connectors.SourceForge;
import gov.osti.connectors.api.GitLabAPI;
import gov.osti.connectors.gitlab.Commit;
import gov.osti.connectors.gitlab.GitLabFile;
import gov.osti.doi.DataCite;
import gov.osti.entity.Agent;
import gov.osti.entity.Contributor;
import gov.osti.entity.MetadataSnapshot;
import gov.osti.entity.DOECodeMetadata;
import gov.osti.entity.DOECodeMetadata.Accessibility;
import gov.osti.entity.DOECodeMetadata.Status;
import gov.osti.entity.Developer;
import gov.osti.entity.DoiReservation;
import gov.osti.entity.ResearchOrganization;
import gov.osti.entity.Site;
import gov.osti.entity.SponsoringOrganization;
import gov.osti.entity.User;
import gov.osti.entity.UserRole;
import gov.osti.entity.UserRole.RoleType;
import gov.osti.indexer.AgentSerializer;
import gov.osti.listeners.DoeServletContextListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.LockTimeoutException;
import javax.persistence.PessimisticLockException;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.ParameterExpression;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Predicate;
import javax.servlet.ServletContext;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.validation.ValidationException;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Context;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresRoles;
import org.apache.shiro.subject.Subject;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.osti.connectors.gitlab.Project;
import gov.osti.entity.RelatedIdentifier;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.codec.binary.Base64InputStream;
import static java.nio.file.StandardCopyOption.*;

/**
 * REST Web Service for Metadata.
 *
 * endpoints:
 *
 * GET
 * metadata/{codeId} - retrieve JSON for record if owner/administrator, optionally in various formats
 * metadata/autopopulate?repo={url} - attempt an auto-populate Connector call for
 * indicated URL, optionally in YAML format
 *
 * POST
 * metadata - send JSON for persisting to the storage layer
 * metadata/submit - send JSON for posting to both ELINK and persistence layer
 * metadata/yaml - send JSON, get YAML back
 *
 * @author ensornl
 */
@Path("metadata")
public class Metadata {
    // inject a Context
    @Context ServletContext context;

    // logger instance
    private static final Logger log = LoggerFactory.getLogger(Metadata.class);
    private static ConnectorFactory factory;

    // SMTP email host name
    private static final String EMAIL_HOST = DoeServletContextListener.getConfigurationProperty("email.host");
    // EMAIL send-from account name
    private static final String EMAIL_FROM = DoeServletContextListener.getConfigurationProperty("email.from");
    // EMAIL address to send to for SUBMISSION/ANNOUNCE
    private static final String EMAIL_SUBMISSION = DoeServletContextListener.getConfigurationProperty("email.notification");
    // URL to indexer services, if configured
    private static String INDEX_URL = DoeServletContextListener.getConfigurationProperty("index.url");
    // absolute filesystem location to store uploaded files, if any
    private static String FILE_UPLOADS = DoeServletContextListener.getConfigurationProperty("file.uploads");
    // absolute filesystem location to store uploaded container images, if any
    private static String CONTAINER_UPLOADS = DoeServletContextListener.getConfigurationProperty("file.containers");
    // absolute filesystem location to store uploaded container images, if any
    private static String CONTAINER_UPLOADS_APPROVED = DoeServletContextListener.getConfigurationProperty("file.containers.approved");
    // API path to archiver services if available
    private static String ARCHIVER_URL = DoeServletContextListener.getConfigurationProperty("archiver.url");
    // get the SITE URL base for applications
    private static String SITE_URL = DoeServletContextListener.getConfigurationProperty("site.url");
    // get Program Manager info
    private static String PM_NAME = DoeServletContextListener.getConfigurationProperty("project.manager.name");
    private static String PM_EMAIL = DoeServletContextListener.getConfigurationProperty("project.manager.email");

    // set pattern for DOI normalization
    private static final Pattern DOI_TRIM_PATTERN = Pattern.compile("(10.\\d{4,9}\\/[-._;()\\/:A-Za-z0-9]+)$");
    private static final Pattern URL_TRIM_PATTERN = Pattern.compile("^(.*)(?<!\\/)\\/?$");

    // create and start a ConnectorFactory for use by "autopopulate" service
    static {
        try {
        factory = ConnectorFactory.getInstance()
                .add(new GitHub())
                .add(new SourceForge())
                .add(new BitBucket())
                .add(new GitLab())
                .build();
        } catch ( IOException e ) {
            log.warn("Configuration failure: " + e.getMessage());
        }
    }

    /**
     * Creates a new instance of MetadataResource
     */
    public Metadata() {
    }

    /**
     * Implement a simple JSON filter to remove named properties.
     */
    @JsonFilter("filter properties by name")
    class PropertyFilterMixIn {}

    // filter out certain attribute names
    protected final static String[] ignoreProperties = {
        "recipientName",
        "recipient_name",
        "recipientEmail",
        "recipient_email",
        "recipientPhone",
        "recipient_phone",
        "owner",
        "workflowStatus",
        "workflow_status",
        "accessLimitations",
        "access_limitations",
        "disclaimers",
        "siteOwnershipCode",
        "site_ownership_code"
    };
    protected static FilterProvider filter = new SimpleFilterProvider()
            .addFilter("filter properties by name",
                    SimpleBeanPropertyFilter.serializeAllExcept(ignoreProperties));

    // ObjectMapper instance for yaml response
    protected static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .addMixIn(Object.class, PropertyFilterMixIn.class)
            .setTimeZone(TimeZone.getDefault());
    // ObjectMapper instance for metadata interchange
    private static final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setTimeZone(TimeZone.getDefault());
    // ObjectMapper specifically for indexing purposes
    protected static final ObjectMapper index_mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setTimeZone(TimeZone.getDefault());
    static {
        // customized serializer module for Agent names consolidation
        SimpleModule module = new SimpleModule();
        module.addSerializer(Agent.class, new AgentSerializer());
        index_mapper.registerModule(module);
    }

    /**
     * Obtain a reserved DOI value if possible.
     *
     * @return a DoiReservation if successful, or null if not
     */
    private static DoiReservation getReservedDoi() {
        EntityManager em = DoeServletContextListener.createEntityManager();
        // set a LOCK TIMEOUT to prevent collision
        em.setProperty("javax.persistence.lock.timeout", 5000);

        try {
            em.getTransaction().begin();

            DoiReservation reservation = em.find(DoiReservation.class, DoiReservation.TYPE, LockModeType.PESSIMISTIC_WRITE);

            if (null==reservation)
                reservation = new DoiReservation();

            reservation.reserve();

            em.merge(reservation);

            em.getTransaction().commit();

            // send it back
            return reservation;
        } catch ( PessimisticLockException | LockTimeoutException e ) {
            log.warn("DOI Reservation, unable to obtain lock.", e);
            return null;
        } finally {
            em.close();
        }
    }

    /**
     * Acquire a unique DOI reservation value.  Requires authentication.
     *
     * Response Code:
     * 200 - JSON contains "doi" element with a new reserved DOI value
     * 500 - a parser or other unexpected error occurred
     *
     * @throws IOException on JSON parsing errors
     * @return JSON containing a new reserved DOI value.
     */
    @GET
    @Path ("reservedoi")
    @Produces (MediaType.APPLICATION_JSON)
    @RequiresAuthentication
    public Response reserveDoi() throws IOException {
        // attempt to reserve a DOI
        DoiReservation reservation = getReservedDoi();

        // if we got a reservation, send it back; otherwise, show a failure
        return (null==reservation) ?
                ErrorResponse.internalServerError("DOI reservation processing failed.").build() :
                Response.ok().entity(mapper.writeValueAsString(reservation)).build();
    }

    /**
     * Look up a record for EDITING, checks authentication and ownership prior
     * to succeeding.
     *
     * Ownership is defined as:  owner and user email match, OR user's roles
     * include the SITE OWNERSHIP CODE of the record, OR user has the "RecordAdmin"
     * special administrative role.
     * Result Codes:
     * 200 - OK, with JSON containing the metadata information
     * 400 - you didn't specify a CODE ID
     * 401 - authentication required
     * 403 - forbidden, logged in user does not have permission to this metadata
     * 404 - requested metadata is not on file
     *
     * @param codeId the CODE ID to look up
     * @param format optional; "yaml" or "xml", default is JSON unless specified
     * @return a Response containing JSON if successful
     */
    @GET
    @Path ("{codeId}")
    @Produces ({MediaType.APPLICATION_JSON, "text/yaml", MediaType.APPLICATION_XML})
    @RequiresAuthentication
    @SuppressWarnings("ConvertToStringSwitch")
    public Response getSingleRecord(@PathParam("codeId") Long codeId, @QueryParam("format") String format) {
        EntityManager em = DoeServletContextListener.createEntityManager();
        Subject subject = SecurityUtils.getSubject();
        User user = (User) subject.getPrincipal();

        // no CODE ID?  Bad request.
        if (null==codeId)
            return ErrorResponse
                    .badRequest("Missing code ID.")
                    .build();

        DOECodeMetadata md = em.find(DOECodeMetadata.class, codeId);

        // no metadata?  404
        if ( null==md )
            return ErrorResponse
                    .notFound("Code ID not on file.")
                    .build();

        // do you have permissions to get this?
        if ( !user.getEmail().equals(md.getOwner()) &&
             !user.hasRole("RecordAdmin") &&
             !user.hasRole("ApprovalAdmin") &&
             !user.hasRole(md.getSiteOwnershipCode()))
            return ErrorResponse
                    .forbidden("Permission denied.")
                    .build();

        // if user is not admin, remove Project Keywords from the result.
        if (!user.hasRole("RecordAdmin"))
            md.setProjectKeywords(null);

        // if YAML is requested, return that; otherwise, default to JSON
        try {
            if ("yaml".equals(format)) {
                // return the YAML (excluding filtered data)
                return
                    Response
                    .ok()
                    .header("Content-Type", "text/yaml")
                    .header("Content-Disposition", "attachment; filename = \"metadata.yml\"")
                    .entity(YAML_MAPPER
                            .writer(filter).writeValueAsString(md))
                    .build();
            } else if ("xml".equals(format)) {
                return Response
                        .ok()
                        .header("Content-Type", MediaType.APPLICATION_XML)
                        .entity(HttpUtil.writeXml(md))
                        .build();
            } else {
                // send back the JSON
                return Response
                        .ok()
                        .header("Content-Type", MediaType.APPLICATION_JSON)
                        .entity(mapper.createObjectNode().putPOJO("metadata", md.toJson()).toString())
                        .build();
            }
        } catch ( IOException e ) {
            log.warn("JSON Output Error", e);
            return ErrorResponse
                    .internalServerError("Unable to process request.")
                    .build();
        }
    }

    /**
     * Intended to be a List of retrieved Metadata records/projects.
     */
    private class RecordsList {
        // the records
    	private List<DOECodeMetadata> records;
        // a total count of a matched query
        private long total;
        // the starting index (0-based)
        private int start;

    	RecordsList(List<DOECodeMetadata> records) {
    		this.records = records;
    	}

        /**
         * Acquire the list of records (a single page of results).
         *
         * @return a List of DOECodeMetadata Objects
         */
        public List<DOECodeMetadata> getRecords() {
                return records;
        }

        /**
         * Set the current page of results.
         *
         * @param records a List of DOECodeMetadata Objects for this page
         */
        public void setRecords(List<DOECodeMetadata> records) {
                this.records = records;
        }

        /**
         * Set a TOTAL count of matches for this search/list.
         *
         * @param count the count to set
         */
        public void setTotal(long count) { total = count; }

        /**
         * Get the TOTAL number of matching rows.
         *
         * @return the total count matched
         */
        public long getTotal() { return total; }

        /**
         * Set the starting index/offset number.
         *
         * @param start the starting index or offset (0 based)
         */
        public void setStart(int start) { this.start = start; }

        /**
         * Get the starting index number, based on 0.
         *
         * @return the starting index number of the records
         */
        public int getStart() { return this.start; }

        /**
         * Get the number of rows on the current "page" of results.
         *
         * @return the number of rows in the current list/page of results.
         */
        public int size() {
            return (null==records) ? 0 : records.size();
        }
    }

    /**
     * Acquire a listing of all records by OWNER.
     *
     * @param rows the number of rows desired (if present)
     * @param start the starting row number (from 0)
     * @return the Metadata information in the desired format
     * @throws JsonProcessingException
     */
    @GET
    @Path ("/projects")
    @Produces (MediaType.APPLICATION_JSON)
    @RequiresAuthentication
    public Response listProjects(
            @QueryParam("rows") int rows,
            @QueryParam("start") int start)
            throws JsonProcessingException {
        EntityManager em = DoeServletContextListener.createEntityManager();

        // get the security user in context
        Subject subject = SecurityUtils.getSubject();
        User user = (User) subject.getPrincipal();

        try {
            Set<String> roles = user.getRoles();

            List<String> allowedSites = UserRole.GetRoleList(RoleType.STANDARD);
            allowedSites.retainAll(roles);

            TypedQuery<DOECodeMetadata> query;
            // admins see ALL PROJECTS
            if (roles.contains("RecordAdmin")) {
                query = em.createQuery("SELECT md FROM DOECodeMetadata md", DOECodeMetadata.class);
            } else if (!allowedSites.isEmpty()) {
                // if you have any allowed site ROLE, it is assumed to be a SITE ADMIN; see all those records plus their own
                query = em.createQuery("SELECT md FROM DOECodeMetadata md WHERE md.owner = :owner OR md.siteOwnershipCode IN :site", DOECodeMetadata.class)
                        .setParameter("owner", user.getEmail())
                        .setParameter("site", allowedSites);
            } else {
                // no roles, you see only YOUR OWN projects
                query = em.createQuery("SELECT md FROM DOECodeMetadata md WHERE md.owner = :owner", DOECodeMetadata.class)
                        .setParameter("owner", user.getEmail());
            }

            // if rows specified, and greater than 100, cap it there
            rows = (rows>100) ? 100 : rows;

            // if pagination elements are present, set them on the query
            if (0!=rows)
                query.setMaxResults(rows);
            if (0!=start)
                query.setFirstResult(start);

            // get a List of records
            RecordsList records = new RecordsList(query.getResultList());
            records.setStart(start);
            ObjectNode recordsObject = mapper.valueToTree(records);

            // lookup previous Snapshot status info for each item
            TypedQuery<MetadataSnapshot> querySnapshot = em.createNamedQuery("MetadataSnapshot.findByCodeIdLastNotStatus", MetadataSnapshot.class)
                    .setParameter("status", DOECodeMetadata.Status.Approved);

            // lookup system Snapshot status info for each item
            TypedQuery<MetadataSnapshot> querySystemSnapshot = em.createNamedQuery("MetadataSnapshot.findByCodeIdAsSystemStatus", MetadataSnapshot.class)
                    .setParameter("status", DOECodeMetadata.Status.Approved);

            JsonNode recordNode = recordsObject.get("records");
            if (recordNode.isArray()) {
                int rowCount = 0;
                for (JsonNode objNode : recordNode) {
                    rowCount++;

                    // skip non-approved records
                    String currentStatus = objNode.get("workflow_status").asText();
                    if (!currentStatus.equalsIgnoreCase("Approved"))
                        continue;

                    // get code_id to find Snapshot
                    long codeId = objNode.get("code_id").asLong();
                    querySnapshot.setParameter("codeId", codeId);
                    querySystemSnapshot.setParameter("codeId", codeId);

                    String lastApprovalFor = "";
                    List<MetadataSnapshot> results = querySnapshot.setMaxResults(1).getResultList();
                    for ( MetadataSnapshot ms : results ) {
                        lastApprovalFor = ms.getSnapshotKey().getSnapshotStatus().toString();
                    }

                    // add "approve as" status indicator to response record, if not blank
                    if (!StringUtils.isBlank(lastApprovalFor))
                        ((ObjectNode) objNode).put("approved_as", lastApprovalFor);

                    String systemStatus = "";
                    List<MetadataSnapshot> resultsSystem = querySystemSnapshot.setMaxResults(1).getResultList();
                    for ( MetadataSnapshot ms : resultsSystem ) {
                        systemStatus = ms.getSnapshotKey().getSnapshotStatus().toString();
                    }

                    // add "system status" indicator to response record, if not blank
                    if (!StringUtils.isBlank(lastApprovalFor))
                        ((ObjectNode) objNode).put("system_status", systemStatus);
                }

                recordsObject.put("total", rowCount);
            }

                return Response
                    .status(Response.Status.OK)
                    .entity(recordsObject.toString())
                    .build();
        } finally {
            em.close();
        }
    }

    /**
     * Acquire a List of records in pending ("Submitted") state, to be approved
     * for indexing and searching.
     *
     * JSON response is of the form:
     *
     * {"records":[{"code_id":n, ...} ],
     *  "start":0, "rows":20, "total":100}
     *
     * Where records is an array of DOECodeMetadata JSON, start is the beginning
     * row number, rows is the number requested (or total if less available),
     * and total is the total number of rows matching the filter.
     *
     * Return Codes:
     * 200 - OK, JSON is returned as above
     * 401 - Unauthorized, login is required
     * 403 - Forbidden, insufficient privileges (role required)
     * 500 - unexpected error
     *
     * @param start the starting row number (from 0)
     * @param rows number of rows desired (0 is unlimited)
     * @param siteCode (optional) a SITE OWNERSHIP CODE to filter by site
     * @param state the WORKFLOW STATE if desired (default Submitted and Announced). One of
     * Approved, Saved, Submitted, or Announced, if supplied.
     * @return JSON of a records response
     */
    @GET
    @Path ("/projects/pending")
    @Consumes (MediaType.APPLICATION_JSON)
    @Produces (MediaType.APPLICATION_JSON)
    @RequiresAuthentication
    @RequiresRoles("ApprovalAdmin")
    public Response listProjectsPending(@QueryParam("start") int start,
                                        @QueryParam("rows") int rows,
                                        @QueryParam("site") String siteCode,
                                        @QueryParam("state") String state) {
        EntityManager em = DoeServletContextListener.createEntityManager();

        try {
            // get a JPA CriteriaBuilder instance
            CriteriaBuilder cb = em.getCriteriaBuilder();
            // create a CriteriaQuery for the COUNT
            CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
            Root<DOECodeMetadata> md = countQuery.from(DOECodeMetadata.class);
            countQuery.select(cb.count(md));

            Expression<String> workflowStatus = md.get("workflowStatus");
            Expression<String> siteOwnershipCode = md.get("siteOwnershipCode");

            // default requested STATE; take Submitted and Announced as the default values if not supplied
            List<DOECodeMetadata.Status> requestedStates = new ArrayList();
            String queryState = (StringUtils.isEmpty(state)) ? "" : state.toLowerCase();
            switch ( queryState ) {
                case "approved":
                    requestedStates.add(DOECodeMetadata.Status.Approved);
                    break;
                case "saved":
                    requestedStates.add(DOECodeMetadata.Status.Saved);
                    break;
                case "submitted":
                    requestedStates.add(DOECodeMetadata.Status.Submitted);
                    break;
                case "announced":
                    requestedStates.add(DOECodeMetadata.Status.Announced);
                    break;
                default:
                    requestedStates.add(DOECodeMetadata.Status.Submitted);
                    requestedStates.add(DOECodeMetadata.Status.Announced);
                    break;
            }

            Predicate statusPredicate = workflowStatus.in(requestedStates);
            ParameterExpression<String> site = cb.parameter(String.class, "site");

            if (null==siteCode) {
                countQuery.where(statusPredicate);
            } else {
                countQuery.where(cb.and(
                        statusPredicate,
                        cb.equal(siteOwnershipCode, site)));
            }
            // query for the COUNT
            TypedQuery<Long> cq = em.createQuery(countQuery);
            cq.setParameter("status", requestedStates);
            if (null!=siteCode)
                cq.setParameter("site", siteCode);

            long rowCount = cq.getSingleResult();
            // rows count should be less than 100 for pagination; 0 is a special case
            rows = (rows>100) ? 100 : rows;

            // create a CriteriaQuery for the ROWS
            CriteriaQuery<DOECodeMetadata> rowQuery = cb.createQuery(DOECodeMetadata.class);
            rowQuery.select(md);

            if (null==siteCode) {
                rowQuery.where(statusPredicate);
            } else {
                rowQuery.where(cb.and(
                        statusPredicate,
                        cb.equal(siteOwnershipCode, site)));
            }

            TypedQuery<DOECodeMetadata> rq = em.createQuery(rowQuery);
            rq.setParameter("status", requestedStates);
            if (null!=siteCode)
                rq.setParameter("site", siteCode);
            rq.setFirstResult(start);
            if (0!=rows) rq.setMaxResults(rows);

            RecordsList records = new RecordsList(rq.getResultList());
            records.setTotal(rowCount);
            records.setStart(start);

            return Response
                    .ok()
                    .entity(mapper.valueToTree(records).toString())
                    .build();
        } finally {
            em.close();
        }
    }

    /**
     * Call to auto-populate Metadata information via Connector, if possible.
     *
     * @param url the REPOSITORY URL to look up information from
     * @param format optionally, the output format ("yaml" supported) JSON is default
     * @return a Metadata instance in the desired output format if information was found
     */
    @GET
    @Path ("/autopopulate")
    @Produces ({MediaType.APPLICATION_JSON, "text/yaml"})
    public Response autopopulate(@QueryParam("repo") String url,
                                 @QueryParam("format") String format) {
        JsonNode resultJson = factory.read(url);

        if (null==resultJson)
            return Response.status(Response.Status.NO_CONTENT).build();

        ObjectNode result = (ObjectNode) resultJson;
        result.remove("code_id");

        // if YAML is requested, return that; otherwise, default to JSON output
        if ("yaml".equals(format)) {
            try {
            return Response
                    .status(Response.Status.OK)
                    .header("Content-Disposition", "attachment; filename = \"metadata.yml\"")
                    .header("Content-Type", "text/yaml")
                    .entity(HttpUtil.writeMetadataYaml(result))
                    .build();
            } catch ( IOException e ) {
                log.warn("YAML conversion error: " + e.getMessage());
                return ErrorResponse
                        .status(Response.Status.INTERNAL_SERVER_ERROR, "YAML conversion error.")
                        .build();
            }
        } else {
            // send back the default JSON response
            return Response
                    .ok()
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .entity(mapper.createObjectNode().putPOJO("metadata", result).toString())
                    .build();
        }
    }

    /**
     * Persist the DOECodeMetadata Object to the persistence layer.  Assumes an
     * open Transaction is already in progress, and it's up to the caller to
     * handle Exceptions or commit as appropriate.
     *
     * If the "code ID" is already present in the Object to store, it will
     * attempt to merge changes; otherwise, a new Object will be instantiated
     * in the database.  Note that any WORKFLOW STATUS present will be preserved,
     * regardless of the incoming one.
     *
     * @param em the EntityManager to interface with the persistence layer
     * @param md the Object to store
     * @param user the User performing this action (must be the OWNER of the
     * record in order to UPDATE)
     * @throws NotFoundException when record to update is not on file
     * @throws IllegalAccessException when attempting to update record not
     * owned by User
     * @throws InvocationTargetException on reflection errors
     */
    private void store(EntityManager em, DOECodeMetadata md, User user) throws NotFoundException,
            IllegalAccessException, InvocationTargetException {
        // fix the open source value before storing
        md.setOpenSource(
                Accessibility.OS.equals(md.getAccessibility()) ||
                Accessibility.ON.equals(md.getAccessibility()));

        ValidatorFactory validators = javax.validation.Validation.buildDefaultValidatorFactory();
        Validator validator = validators.getValidator();

        // must be RecordAdmin user in order to add/update PROJECT KEYWORDS
        List<String> projectKeywords = md.getProjectKeywords();
        if (projectKeywords != null && !projectKeywords.isEmpty() && !user.hasRole("RecordAdmin") && !user.hasRole("ApprovalAdmin"))
            throw new ValidationException("Project Keywords can only be set by authorized users.");

        // if there's a CODE ID, attempt to look up the record first and
        // copy attributes into it
        if ( null==md.getCodeId() || 0==md.getCodeId()) {
            // perform length validations on Bean
            Set<ConstraintViolation<DOECodeMetadata>> violations = validator.validate(md);
            if (!violations.isEmpty()) {
                List<String> reasons = new ArrayList<>();

                violations.stream().forEach(violation->{
                    reasons.add(violation.getMessage());
                });
                throw new BadRequestException (ErrorResponse.badRequest(reasons).build());
            }
            em.persist(md);
        } else {
            DOECodeMetadata emd = em.find(DOECodeMetadata.class, md.getCodeId());

            if ( null!=emd ) {
                // to Approve, user must be an ApprovalAdmin and record must be previously Submitted/Announced
                if (DOECodeMetadata.Status.Approved.equals(md.getWorkflowStatus())) {
                    if (!(user.hasRole("ApprovalAdmin")
                            && (DOECodeMetadata.Status.Submitted.equals(emd.getWorkflowStatus())
                                    || DOECodeMetadata.Status.Announced.equals(emd.getWorkflowStatus()))))
                        throw new IllegalAccessException("Invalid approval attempt.");
                }
                // otherwise, must be the OWNER, SITE ADMIN, or RecordAdmin in order to UPDATE
                else if (!user.getEmail().equals(emd.getOwner())
                        && !user.hasRole(emd.getSiteOwnershipCode())
                        && !user.hasRole("RecordAdmin"))
                    throw new IllegalAccessException("Invalid access attempt.");

                // to Save, item must be non-existant, or already in Saved workflow status (if here, we know it exists)
                if (Status.Saved.equals(md.getWorkflowStatus()) && !Status.Saved.equals(emd.getWorkflowStatus()))
                    throw new BadRequestException (ErrorResponse.badRequest("Save cannot be performed after a record has been Submitted or Announced.").build());

                // these fields WILL NOT CHANGE on edit/update
                md.setOwner(emd.getOwner());
                md.setSiteOwnershipCode(emd.getSiteOwnershipCode());
                // if there's ALREADY a DOI, and we have been SUBMITTED/APPROVED, keep it
                if (StringUtils.isNotEmpty(emd.getDoi()) &&
                    (Status.Submitted.equals(emd.getWorkflowStatus()) ||
                     Status.Approved.equals(emd.getWorkflowStatus())))
                    md.setDoi(emd.getDoi());

                // do not modify AutoBackfill RI info
                List<RelatedIdentifier> originalList = emd.getRelatedIdentifiers();
                List<RelatedIdentifier> newList = md.getRelatedIdentifiers();
                // if there is a New List and a non-empty Original List, then process RI info
                if (newList != null && originalList != null && !originalList.isEmpty()) {
                    // get AutoBackfill data
                    List<RelatedIdentifier> autoRIList = getSourceRi(originalList, RelatedIdentifier.Source.AutoBackfill);

                    // restore any modified Auto data
                    newList.removeAll(autoRIList); // always remove match
                    newList.addAll(autoRIList); // add back, if needed

                    md.setRelatedIdentifiers(newList);
                }

                // perform length validations on Bean
                Set<ConstraintViolation<DOECodeMetadata>> violations = validator.validate(md);
                if (!violations.isEmpty()) {
                    List<String> reasons = new ArrayList<>();

                    violations.stream().forEach(violation->{
                        reasons.add(violation.getMessage());
                    });
                    throw new BadRequestException (ErrorResponse.badRequest(reasons).build());
                }

                // found it, "merge" Bean attributes
                BeanUtilsBean noNulls = new NoNullsBeanUtilsBean();
                noNulls.copyProperties(emd, md);

                // if the RELEASE DATE was set, it might have been "cleared" (set to null)
                // and thus ignored by the Bean copy; this sets the value regardless if setReleaseDate() got called
                if (md.hasSetReleaseDate())
                    emd.setReleaseDate(md.getReleaseDate());


                // what comes back needs to be complete:
                noNulls.copyProperties(md, emd);

                // EntityManager should handle this attached Object
                // NOTE: the returned Object is NOT ATTACHED to the EntityManager
            } else {
                // can't find record to update, that's an error
                log.warn("Unable to locate record for " + md.getCodeId() + " to update.");
                throw new NotFoundException("Record Code ID " + md.getCodeId() + " not on file.");
            }
        }
    }

    /**
     * Convert incoming JSON object of Metadata information to YAML if possible.
     *
     * @param object JSON of the Metadata information
     * @return YAML of that JSON object, if mappable
     */
    @POST
    @Consumes (MediaType.APPLICATION_JSON)
    @Produces ("text/yaml")
    @Path ("/yaml")
    public Response asYAML(String object) {
        try {
            DOECodeMetadata md = DOECodeMetadata.parseJson(new StringReader(object));

            return Response
                    .status(Response.Status.OK)
                    .entity(HttpUtil.writeMetadataYaml(md))
                    .build();
        } catch ( IOException e ) {
            log.warn("YAML conversion error: " + e.getMessage());
            return ErrorResponse
                    .internalServerError("YAML conversion error.")
                    .build();
        }
    }

    /**
     * Send this Metadata to the ARCHIVER external support process.
     *
     * Needs a CODE ID and one of either an ARCHIVE FILE or REPOSITORY LINK.
     *
     * If nothing supplied to archive, do nothing.
     *
     * @param codeId the CODE ID for this METADATA
     * @param repositoryLink (optional) the REPOSITORY LINK value, or null if none
     * @param archiveFile (optional) the File recently uploaded to ARCHIVE, or null if none
     * @param archiveContainer (optional) the Container recently uploaded to ARCHIVE, or null if none
     * @throws IOException on IO transmission errors
     */
    private static void sendToArchiver(Long codeId, String repositoryLink, File archiveFile, File archiveContainer) throws IOException {
        if ( "".equals(ARCHIVER_URL) )
            return;

        // Nothing sent?
        if (StringUtils.isBlank(repositoryLink) && null==archiveFile && null==archiveContainer)
            return;

        // set up a connection
        CloseableHttpClient hc =
                HttpClientBuilder
                .create()
                .setDefaultRequestConfig(RequestConfig
                        .custom()
                        .setSocketTimeout(300000)
                        .setConnectTimeout(300000)
                        .setConnectionRequestTimeout(300000)
                        .build())
                .build();

        try {
            HttpPost post = new HttpPost(ARCHIVER_URL);
            // attributes to send
            ObjectNode request = mapper.createObjectNode();
            request.put("code_id", codeId);
            request.put("repository_link", repositoryLink);

            // determine if there's a file to send or not
            if (null==archiveFile && null==archiveContainer) {
                post.setHeader("Content-Type", "application/json");
                post.setHeader("Accept", "application/json");

                post.setEntity(new StringEntity(request.toString(), "UTF-8"));
            } else {
                MultipartEntityBuilder mpe = MultipartEntityBuilder
                        .create()
                        .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                        .addPart("project", new StringBody(request.toString(), ContentType.APPLICATION_JSON));

                if (archiveFile != null)
                    mpe.addPart("file", new FileBody(archiveFile, ContentType.DEFAULT_BINARY));

                if (archiveContainer != null)
                    mpe.addPart("container", new FileBody(archiveContainer, ContentType.DEFAULT_BINARY));

                post.setEntity(mpe.build());
            }
            HttpResponse response = hc.execute(post);

            int statusCode = response.getStatusLine().getStatusCode();

            if (HttpStatus.SC_OK!=statusCode && HttpStatus.SC_CREATED!=statusCode) {
                throw new IOException ("Archiver Error: " + EntityUtils.toString(response.getEntity()));
            }
        } catch ( IOException e ) {
            log.warn("Archiver request error: " + e.getMessage());
            throw e;
        } finally {
            try {
                if (null!=hc) hc.close();
            } catch ( IOException e ) {
                log.warn("Close Error: " + e.getMessage());
            }
        }
    }

    /**
     * Validate accepted file types.
     *
     * @param fileName the uploaded filename to evaluate.
     * @param containerName the uploaded container image filename to evaluate.
     */
    private static void validateUploads(FormDataContentDisposition fileInfo, FormDataContentDisposition containerInfo) {
        // evaluate file upload
        if (fileInfo != null && !StringUtils.isBlank(fileInfo.getFileName())) {
            String fileName = fileInfo.getFileName();
            Pattern filePattern = Pattern.compile("[.](?:zip|tgz|tar(?:[.](?:gz|bz2))?)$");
            Matcher m = filePattern.matcher(fileName);
            if (!m.find())
                throw new ValidationException("File upload failed!  File must be of type: .zip, .tar, .tgz, .tar.gz, .tar.bz2");
        }

        // evaluate container upload
        if (containerInfo != null  && !StringUtils.isBlank(containerInfo.getFileName())) {
            String fileName = containerInfo.getFileName();
            Pattern containerPattern = Pattern.compile("[.](?:simg|tgz|tar(?:[.]gz)?)$");
            Matcher m = containerPattern.matcher(fileName);
            if (!m.find())
                throw new ValidationException("Container image upload failed!  File must be of type: .tar, .tgz, .tar.gz, .simg");
        }
    }

    /**
     * Get specific Source RI from metadata.
     *
     * @param md the Metadata to evaluate.
     * @return Updated DOECodeMetadata object.
     */
    private static List<RelatedIdentifier> getSourceRi(List<RelatedIdentifier> list, RelatedIdentifier.Source source) {
        // get detached list of RI to check
        List<RelatedIdentifier> riList = new ArrayList<>();
        riList.addAll(list);

        // filter to targeted RI
        riList = riList.stream().filter(p -> p.getSource() == source
        ).collect(Collectors.toList());

        return riList;
    }

    /**
     * Remove non-indexable New/Previous RI from metadata.
     *
     * @param em the EntityManager to control commits.
     * @param md the Metadata to evaluate.
     * @return Updated DOECodeMetadata object.
     */
    private static DOECodeMetadata removeNonIndexableRi(EntityManager em, DOECodeMetadata md) throws IOException {
        // need a detached copy of the RI data
        DOECodeMetadata alteredMd = new DOECodeMetadata();
        BeanUtilsBean bean = new BeanUtilsBean();

        try {
            bean.copyProperties(alteredMd, md);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            // log issue, swallow error
            String msg = "NonIndexable RI Removal Bean Error: " + ex.getMessage();
            throw new IOException(msg);
        }

        TypedQuery<MetadataSnapshot> querySnapshot = em.createNamedQuery("MetadataSnapshot.findByDoiAndStatus", MetadataSnapshot.class)
                .setParameter("status", DOECodeMetadata.Status.Approved);

        // get detached list of RI to check
        List<RelatedIdentifier> riList = new ArrayList<>();
        riList.addAll(alteredMd.getRelatedIdentifiers());

        // filter to targeted RI
        List<RelatedIdentifier> filteredRiList = riList.stream().filter(p -> p.getIdentifierType() == RelatedIdentifier.Type.DOI
                && (p.getRelationType() == RelatedIdentifier.RelationType.IsNewVersionOf
                || p.getRelationType() == RelatedIdentifier.RelationType.IsPreviousVersionOf)
        ).collect(Collectors.toList());

        // track removals
        List<RelatedIdentifier> removalList = new ArrayList<>();

        for ( RelatedIdentifier ri : filteredRiList ) {
            // lookup by Snapshot by current DOI
            querySnapshot.setParameter("doi", ri.getIdentifierValue());

            List<MetadataSnapshot> results = querySnapshot.getResultList();

            // if no results, keep, otherwise remove unless there is a minted version found
            boolean remove = !results.isEmpty();
            for ( MetadataSnapshot ms : results ) {
                    if (ms.getDoiIsMinted()) {
                        remove = false;
                        break;
                    }
            }

            if (remove)
                removalList.add(ri);
        }

        // perform removals, as needed, and update
        if (!removalList.isEmpty()) {
            riList.removeAll(removalList);
            alteredMd.setRelatedIdentifiers(riList);
        }

        return alteredMd;
    }

    /**
     * Get previous snapshot info for use in backfill process that occurs after current snapshot is updated.
     *
     * @param em the EntityManager to control commits.
     * @param md the Metadata to evaluate for RI backfilling.
     * @return List of RelatedIdentifier objects.
     */
    private List<RelatedIdentifier> getPreviousRiList(EntityManager em, DOECodeMetadata md) throws IOException {
        // if current project has no DOI, there is nothing to process later on, so do not pull previous info
        if (StringUtils.isBlank(md.getDoi()))
            return null;

        long codeId = md.getCodeId();

        // pull last know Approved info
        TypedQuery<MetadataSnapshot> querySnapshot = em.createNamedQuery("MetadataSnapshot.findByCodeIdAndStatus", MetadataSnapshot.class)
                .setParameter("codeId", codeId)
                .setParameter("status", DOECodeMetadata.Status.Approved);

        List<MetadataSnapshot> results = querySnapshot.setMaxResults(1).getResultList();

        // get previous Approved RI list, if applicable
        List<RelatedIdentifier> previousList = new ArrayList<>();
        for ( MetadataSnapshot ms : results ) {
            try {
                DOECodeMetadata pmd = DOECodeMetadata.parseJson(new StringReader(ms.getJson()));

                previousList = pmd.getRelatedIdentifiers();

                if (previousList == null)
                    previousList = new ArrayList<>();

                // filter to targeted RI
                previousList = previousList.stream().filter(p -> p.getIdentifierType() == RelatedIdentifier.Type.DOI
                        && (p.getRelationType() == RelatedIdentifier.RelationType.IsNewVersionOf
                        || p.getRelationType() == RelatedIdentifier.RelationType.IsPreviousVersionOf)
                ).collect(Collectors.toList());

            } catch (IOException ex) {
                // unable to parse JSON, but for this process
                String msg = "Unable to parse previously 'Approved' Snapshot JSON for " + codeId + ": " + ex.getMessage();
                throw new IOException(msg);
            }
            break; // failsafe: there should only ever be one, at most
        }

        return previousList;
    }

    /**
     * Add/Remove backfill RI information.
     * To modify source items, you must be an OSTI admin, project owner, or site admin.
     *
     * @param em the EntityManager to control commits.
     * @param md the Metadata to evaluate for RI updating.
     * @param previousList the RelatedIdentifiers from previous Approval.
     */
    private void backfillProjects(EntityManager em, DOECodeMetadata md, List<RelatedIdentifier> previousList) throws IllegalAccessException, IOException {
        // if current project has no DOI, there is nothing to process
        if (StringUtils.isBlank(md.getDoi()))
            return;

        // get current list of RI info, for backfill additions
        List<RelatedIdentifier> additionList = md.getRelatedIdentifiers();

        if (additionList == null)
            additionList = new ArrayList<>();

        // filter additions to targeted RI
        additionList = additionList.stream().filter(p -> p.getIdentifierType() == RelatedIdentifier.Type.DOI
                && (p.getRelationType() == RelatedIdentifier.RelationType.IsNewVersionOf
                || p.getRelationType() == RelatedIdentifier.RelationType.IsPreviousVersionOf)
        ).collect(Collectors.toList());

        if (previousList == null)
            previousList = new ArrayList<>();

        // previous relations no longer defined must be removed
        previousList.removeAll(additionList);

        // store details about what will need sent to OSTI and re-indexed
        Map<Long, DOECodeMetadata> backfillSendToIndex = new HashMap<>();
        Map<Long, DOECodeMetadata> backfillSendToOsti = new HashMap<>();

        // define needed queries
        TypedQuery<DOECodeMetadata> deleteQuery = em.createNamedQuery("DOECodeMetadata.findByDoiAndRi", DOECodeMetadata.class);
        TypedQuery<DOECodeMetadata> addQuery = em.createNamedQuery("DOECodeMetadata.findByDoi", DOECodeMetadata.class);
        TypedQuery<MetadataSnapshot> snapshotQuery = em.createNamedQuery("MetadataSnapshot.findByCodeIdAndStatus", MetadataSnapshot.class);
        TypedQuery<MetadataSnapshot> querySnapshot = em.createNamedQuery("MetadataSnapshot.findByCodeIdLastNotStatus", MetadataSnapshot.class)
                .setParameter("status", DOECodeMetadata.Status.Approved);

        List<RelatedIdentifier> backfillSourceList;

        // for each BackfillType, perform actions:  delete obsolete previous info / add new info
        for (RelatedIdentifier.BackfillType backfillType : RelatedIdentifier.BackfillType.values()) {
            // previous relations no longer defined must be removed, current relations need to be added
            backfillSourceList = backfillType == RelatedIdentifier.BackfillType.Deletion ? previousList : additionList;

            // if there is no list to process, skip
            if (backfillSourceList == null || backfillSourceList.isEmpty())
                continue;

            for (RelatedIdentifier ri : backfillSourceList) {
                // get inverse relation
                RelatedIdentifier inverseRelation = new RelatedIdentifier(ri);
                inverseRelation.setRelationType(ri.getRelationType().inverse());
                inverseRelation.setIdentifierValue(md.getDoi());
                inverseRelation.setSource(RelatedIdentifier.Source.AutoBackfill);

                List<RelatedIdentifier> targetedList = Arrays.asList(inverseRelation);

                List<DOECodeMetadata> results = new ArrayList<>();
                List<MetadataSnapshot> snapshotResults;

                if (backfillType == RelatedIdentifier.BackfillType.Deletion) {
                    // check for the existance of the inverse relation
                    deleteQuery.setParameter("doi", ri.getIdentifierValue())
                            .setParameter("type", inverseRelation.getIdentifierType())
                            .setParameter("value", inverseRelation.getIdentifierValue())
                            .setParameter("relType", inverseRelation.getRelationType());

                    results = deleteQuery.getResultList();
                }
                else if (backfillType == RelatedIdentifier.BackfillType.Addition) {
                    // lookup target DOI
                    addQuery.setParameter("doi", ri.getIdentifierValue());

                    results = addQuery.getResultList();
                }

                // update RI where needed
                for ( DOECodeMetadata bmd : results ) {
                    // target CODE ID and Workflow Status
                    Long codeId = bmd.getCodeId();
                    DOECodeMetadata.Status status = bmd.getWorkflowStatus();

                    List<RelatedIdentifier> updateList = bmd.getRelatedIdentifiers();

                    if (updateList == null)
                        updateList = new ArrayList<>();

                    // get User data
                    List<RelatedIdentifier> userRIList = getSourceRi(updateList, RelatedIdentifier.Source.User);

                     // update metadata RI info
                    updateList.removeAll(targetedList); // always remove match
                    if (backfillType == RelatedIdentifier.BackfillType.Addition)
                        updateList.addAll(targetedList); // add back, if needed

                    // restore any modified User data
                    updateList.removeAll(userRIList); // always remove match
                    updateList.addAll(userRIList); // add back, if needed

                    // save changes
                    bmd.setRelatedIdentifiers(updateList);

                    // update snapshot metadata
                    snapshotQuery.setParameter("codeId", codeId)
                            .setParameter("status", status);

                    snapshotResults = snapshotQuery.getResultList();

                    // update snapshot RI, for same status, where needed
                    for ( MetadataSnapshot ms : snapshotResults ) {
                        try {
                            DOECodeMetadata smd = DOECodeMetadata.parseJson(new StringReader(ms.getJson()));

                            List<RelatedIdentifier> snapshotList = smd.getRelatedIdentifiers();

                            if (snapshotList == null)
                                snapshotList = new ArrayList<>();

                            // get User data
                            userRIList = getSourceRi(snapshotList, RelatedIdentifier.Source.User);

                            // update snapshot RI info, if needed
                            snapshotList.removeAll(targetedList); // always remove match
                            if (backfillType == RelatedIdentifier.BackfillType.Addition)
                                snapshotList.addAll(targetedList); // add back, if needed

                            // restore any modified User data
                            snapshotList.removeAll(userRIList); // always remove match
                            snapshotList.addAll(userRIList); // add back, if needed

                            // save changes to Snapshot
                            smd.setRelatedIdentifiers(snapshotList);
                            ms.setJson(smd.toJson().toString());

                            // log updated, Approved snapshot info for post-backfill actions
                            if (status == DOECodeMetadata.Status.Approved) {
                                // log for re-indexing
                                backfillSendToIndex.put(codeId, smd);


                                // lookup snapshot status info, prior to Approval
                                querySnapshot.setParameter("codeId", codeId);

                                List<MetadataSnapshot> previousResults = querySnapshot.setMaxResults(1).getResultList();
                                for ( MetadataSnapshot pms : previousResults ) {
                                    DOECodeMetadata.Status lastApprovalFor = pms.getSnapshotKey().getSnapshotStatus();

                                    // if Approved for Announcement, log for OSTI
                                    if (lastApprovalFor == DOECodeMetadata.Status.Announced)
                                        backfillSendToOsti.put(codeId, smd);

                                    break; // failsafe, but should only be at most one item returned
                                }
                            }
                        } catch (IOException ex) {
                            // unable to parse JSON, but for this process
                            String msg = "Unable to parse '" + ms.getSnapshotKey().getSnapshotStatus() + "' Snapshot JSON for " + ms.getSnapshotKey().getCodeId() + ": " + ex.getMessage();
                            throw new IOException(msg);
                        }
                    }
                }
            }
        }

        // update OSTI, as needed
        for (Map.Entry<Long, DOECodeMetadata> entry : backfillSendToOsti.entrySet()) {
            sendToOsti(em, entry.getValue());
        }

        // update Index, as needed
        for (Map.Entry<Long, DOECodeMetadata> entry : backfillSendToIndex.entrySet()) {
            sendToIndex(em, entry.getValue());
        }
    }

    /**
     * Attempt to send this Metadata information to the indexing service configured.
     * If no service is configured, do nothing.
     *
     * @param em the related EntityManager
     * @param md the Metadata to send
     */
    private static void sendToIndex(EntityManager em, DOECodeMetadata md) {
        // if indexing is not configured, skip this step
        if ("".equals(INDEX_URL))
            return;

        // set some reasonable default timeouts
        RequestConfig rc = RequestConfig
                .custom()
                .setSocketTimeout(60000)
                .setConnectTimeout(60000)
                .setConnectionRequestTimeout(60000)
                .build();
        // create an HTTP client to request through
        CloseableHttpClient hc =
                HttpClientBuilder
                .create()
                .setDefaultRequestConfig(rc)
                .build();
        try {
            // do not index DOE CODE New/Previous DOI related identifiers if Approved without a Release Date
            DOECodeMetadata indexableMd = removeNonIndexableRi(em, md);

            // construct a POST submission to the indexer service
            HttpPost post = new HttpPost(INDEX_URL);
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Accept", "application/json");
            // add JSON String to index for later display/search
            ObjectNode node = (ObjectNode)index_mapper.valueToTree(indexableMd);
            node.put("json", indexableMd.toJson().toString());
            post.setEntity(new StringEntity(node.toString(), "UTF-8"));

            HttpResponse response = hc.execute(post);

            if ( HttpStatus.SC_OK!=response.getStatusLine().getStatusCode() ) {
                log.warn("Indexing Error occurred for ID=" + md.getCodeId());
                log.warn("Message: " + EntityUtils.toString(response.getEntity()));
            }
        } catch ( IOException e ) {
            log.warn("Indexing Error: " + e.getMessage() + " ID=" + md.getCodeId());
        } finally {
            try {
                if (null!=hc) hc.close();
            } catch ( IOException e ) {
                log.warn("Index Close Error: " + e.getMessage());
            }
        }
    }

    /**
     * Perform SAVE workflow on indicated METADATA.
     *
     * @param json the JSON String containing the metadata to SAVE
     * @param file a FILE associated with this record if any
     * @param fileInfo the FILE disposition information if any
     * @param container a CONTAINER IMAGE associated with this record if any
     * @param containerInfo the CONTAINER IMAGE disposition of information if any
     * @return a Response containing the JSON of the saved record if successful,
     * or error information if not
     */
    private Response doSave(String json, InputStream file, FormDataContentDisposition fileInfo
            , InputStream container, FormDataContentDisposition containerInfo) {
        EntityManager em = DoeServletContextListener.createEntityManager();
        Subject subject = SecurityUtils.getSubject();
        User user = (User) subject.getPrincipal();

        try {
            validateUploads(fileInfo, containerInfo);

            DOECodeMetadata md = DOECodeMetadata.parseJson(new StringReader(json));

            em.getTransaction().begin();

            performDataNormalization(md);

            md.setWorkflowStatus(Status.Saved); // default to this
            md.setOwner(user.getEmail()); // this User should OWN it
            md.setSiteOwnershipCode(user.getSiteId());

            store(em, md, user);

            // re-attach metadata to transaction in order to store any changes beyond this point
            md = em.find(DOECodeMetadata.class, md.getCodeId());

            // if there's a FILE associated here, store it
            if ( null!=file && null!=fileInfo ) {
                try {
                    String fileName = writeFile(file, md.getCodeId(), fileInfo.getFileName(), FILE_UPLOADS);
                    md.setFileName(fileName);
                } catch ( IOException e ) {
                    log.error ("File Upload Failed: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("File upload failed.")
                            .build();
                }
            }

            // if there's a CONTAINER IMAGE associated here, store it
            if ( null!=container && null!=containerInfo ) {
                try {
                    String containerName = writeFile(container, md.getCodeId(), containerInfo.getFileName(), CONTAINER_UPLOADS);
                    md.setContainerName(containerName);
                } catch ( IOException e ) {
                    log.error ("Container Image Upload Failed: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("Container Image upload failed.")
                            .build();
                }
            }

            // we're done here
            em.getTransaction().commit();

            return Response
                    .status(200)
                    .entity(mapper.createObjectNode().putPOJO("metadata", md.toJson()).toString())
                    .build();
        } catch ( BadRequestException e ) {
            return e.getResponse();
        } catch ( NotFoundException e ) {
            return ErrorResponse
                    .notFound(e.getMessage())
                    .build();
        } catch ( IllegalAccessException e ) {
            log.warn("Persistence Error:  Invalid update attempt from " + user.getEmail());
            log.warn("Message: " + e.getMessage());
            return ErrorResponse
                    .forbidden("Unable to persist update for indicated record.")
                    .build();
        } catch ( ValidationException e ) {
            log.warn("Validation Error: " + e.getMessage());
            return ErrorResponse
                    .badRequest(e.getMessage())
                    .build();
        } catch ( IOException | InvocationTargetException e ) {
            if (em.getTransaction().isActive())
                em.getTransaction().rollback();

            log.warn("Persistence Error: " + e.getMessage());
            return ErrorResponse
                    .internalServerError("Save IO Error: " + e.getMessage())
                    .build();
        } finally {
            em.close();
        }
    }

    /**
     * Handle SUBMIT workflow logic.
     *
     * @param json JSON String containing the METADATA object to SUBMIT
     * @param file (optional) a FILE associated with this METADATA
     * @param fileInfo (optional) the FILE disposition information, if any
     * @param container (optional) a CONTAINER IMAGE associated with this METADATA
     * @param containerInfo (optional) the CONTAINER IMAGE disposition information, if any
     * @return an appropriate Response object to the caller
     */
    private Response doSubmit(String json, InputStream file, FormDataContentDisposition fileInfo
            , InputStream container, FormDataContentDisposition containerInfo) {
        EntityManager em = DoeServletContextListener.createEntityManager();
        Subject subject = SecurityUtils.getSubject();
        User user = (User) subject.getPrincipal();

        try {
            validateUploads(fileInfo, containerInfo);

            DOECodeMetadata md = DOECodeMetadata.parseJson(new StringReader(json));

            Long currentCodeId = md.getCodeId();
            boolean previouslySaved = false;
            if (currentCodeId != null) {
                DOECodeMetadata emd = em.find(DOECodeMetadata.class, currentCodeId);

                if (emd != null)
                    previouslySaved = Status.Saved.equals(emd.getWorkflowStatus());
            }

            // lookup Announced Snapshot status
            TypedQuery<MetadataSnapshot> querySnapshot = em.createNamedQuery("MetadataSnapshot.findByCodeIdAndStatus", MetadataSnapshot.class)
                    .setParameter("codeId", currentCodeId)
                    .setParameter("status", DOECodeMetadata.Status.Announced);

            List<MetadataSnapshot> results = querySnapshot.setMaxResults(1).getResultList();
            if (results.size() > 0) {
                log.error ("Cannot Submit, Previously Announced: " + currentCodeId);
                return ErrorResponse
                        .internalServerError("This record was previously Announced to E-Link, if you need to update the metadata, please change your endpoint to \"/announce.\"")
                        .build();
            }

            em.getTransaction().begin();

            performDataNormalization(md);

            // set the ownership and workflow status
            md.setOwner(user.getEmail());
            md.setWorkflowStatus(Status.Submitted);
            md.setSiteOwnershipCode(user.getSiteId());

            // store it
            store(em, md, user);

            // re-attach metadata to transaction in order to store any changes beyond this point
            md = em.find(DOECodeMetadata.class, md.getCodeId());

            // if there's a FILE associated here, store it
            String fullFileName = "";
            if ( null!=file && null!=fileInfo ) {
                try {
                    fullFileName = writeFile(file, md.getCodeId(), fileInfo.getFileName(), FILE_UPLOADS);
                    md.setFileName(fullFileName);
                } catch ( IOException e ) {
                    log.error ("File Upload Failed: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("File upload failed.")
                            .build();
                }
            }

            // if there's a CONTAINER IMAGE associated here, store it
            String fullContainerName = "";
            if ( null!=container && null!=containerInfo ) {
                try {
                    fullContainerName = writeFile(container, md.getCodeId(), containerInfo.getFileName(), CONTAINER_UPLOADS);
                    md.setContainerName(fullContainerName);
                } catch ( IOException e ) {
                    log.error ("Container Image Upload Failed: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("Container Image upload failed.")
                            .build();
                }
            }


            // check validations for Submitted workflow
            List<String> errors = validateSubmit(md);
            
            // if BUSINESS, check Sponsor Orgs on SUBMIT
            if (DOECodeMetadata.Type.B.equals(md.getSoftwareType()))
                errors.addAll(validateSponsorOrgs(md));

            if ( !errors.isEmpty() ) {
                // generate a JSONAPI errors object
                return ErrorResponse
                        .badRequest(errors)
                        .build();
            }

            // create OSTI Hosted project, as needed
            try {
                // process local GitLab, if needed
                processOSTIGitLab(md);
            } catch ( Exception e ) {
                log.error("OSTI GitLab failure: " + e.getMessage());
                return ErrorResponse
                        .internalServerError("Unable to create OSTI Hosted project: " + e.getMessage())
                        .build();
            }

            // send this file upload along to archiver if configured
            try {
                // if no file/container, but previously Saved with a file/container, we need to attach to those streams and send to Archiver
                if (previouslySaved) {
                    if (null==file && !StringUtils.isBlank(md.getFileName())) {
                        java.nio.file.Path destination = Paths.get(FILE_UPLOADS, String.valueOf(md.getCodeId()), md.getFileName());
                        fullFileName = destination.toString();
                        file = Files.newInputStream(destination);
                    }
                    if (null==container && !StringUtils.isBlank(md.getContainerName())) {
                        java.nio.file.Path destination = Paths.get(CONTAINER_UPLOADS, String.valueOf(md.getCodeId()), md.getContainerName());
                        fullContainerName = destination.toString();
                        container = Files.newInputStream(destination);
                    }
                }

                // if a FILE or CONTAINER was sent, create a File Object from it
                File archiveFile = (null==file) ? null : new File(fullFileName);
                File archiveContainer = null; //(null==container) ? null : new File(fullContainerName);
                if (DOECodeMetadata.Accessibility.CO.equals(md.getAccessibility()))
                    // if CO project type, no need to archive the repo because it is local GitLab
                    sendToArchiver(md.getCodeId(), null, archiveFile, archiveContainer);
                else
                    sendToArchiver(md.getCodeId(), md.getRepositoryLink(), archiveFile, archiveContainer);
            } catch ( IOException e ) {
                log.error("Archiver call failure: " + e.getMessage());
                return ErrorResponse
                        .internalServerError("Unable to archive project.")
                        .build();
            }

            // send to DataCite if needed (and there is a RELEASE DATE set)
            if ( null!=md.getDoi() && null!=md.getReleaseDate() ) {
                try {
                    DataCite.register(md);
                } catch ( IOException e ) {
                    // tell why the DataCite registration failed
                    log.warn("DataCite ERROR: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("The DOI registration service is currently unavailable, please try to submit your record later. If the issue persists, please contact doecode@osti.gov.")
                            .build();
                }
            }

            // store the snapshot copy of Metadata
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.getSnapshotKey().setCodeId(md.getCodeId());
            snapshot.getSnapshotKey().setSnapshotStatus(md.getWorkflowStatus());
            snapshot.setDoi(md.getDoi());
            snapshot.setDoiIsMinted(md.getReleaseDate() != null);
            snapshot.setJson(md.toJson().toString());

            em.merge(snapshot);

            // commit it
            em.getTransaction().commit();

            // send NOTIFICATION if configured to do so
            sendStatusNotification(md);

            // we are done here
            return Response
                    .ok()
                    .entity(mapper.createObjectNode().putPOJO("metadata", md.toJson()).toString())
                    .build();
        } catch ( BadRequestException e ) {
            return e.getResponse();
        } catch ( NotFoundException e ) {
            return ErrorResponse
                    .notFound(e.getMessage())
                    .build();
        } catch ( IllegalAccessException e ) {
            log.warn("Persistence Error: Unable to update record, invalid owner: " + user.getEmail());
            log.warn("Message: " + e.getMessage());
            return ErrorResponse
                    .forbidden("Logged in User is not allowed to modify this record.")
                    .build();
        } catch ( ValidationException e ) {
            log.warn("Validation Error: " + e.getMessage());
            return ErrorResponse
                    .badRequest(e.getMessage())
                    .build();
        } catch ( IOException | InvocationTargetException e ) {
            if ( em.getTransaction().isActive())
                em.getTransaction().rollback();

            log.warn("Persistence Error Submitting: " + e.getMessage());
            return ErrorResponse
                    .internalServerError("Persistence error submitting record.")
                    .build();
        } finally {
            em.close();
        }
    }

    /**
     * Perform ANNOUNCE workflow operation, optionally with associated file uploads.
     *
     * @param json String containing JSON of the Metadata to ANNOUNCE
     * @param file the FILE (if any) to attach to this metadata
     * @param fileInfo file disposition information if FILE present
     * @return a Response containing the JSON of the submitted record if successful, or
     * error information if not
     */
    private Response doAnnounce(String json, InputStream file, FormDataContentDisposition fileInfo
            , InputStream container, FormDataContentDisposition containerInfo) {
        EntityManager em = DoeServletContextListener.createEntityManager();
        Subject subject = SecurityUtils.getSubject();
        User user = (User) subject.getPrincipal();

        try {
            validateUploads(fileInfo, containerInfo);

            DOECodeMetadata md = DOECodeMetadata.parseJson(new StringReader(json));

            Long currentCodeId = md.getCodeId();
            boolean previouslySaved = false;
            if (currentCodeId != null) {
                DOECodeMetadata emd = em.find(DOECodeMetadata.class, currentCodeId);

                if (emd != null)
                    previouslySaved = Status.Saved.equals(emd.getWorkflowStatus());
            }

            em.getTransaction().begin();

            performDataNormalization(md);

            // set the OWNER
            md.setOwner(user.getEmail());
            // set the WORKFLOW STATUS
            md.setWorkflowStatus(Status.Announced);
            // set the SITE
            md.setSiteOwnershipCode(user.getSiteId());
            // if there is NO DOI set, get one
            if (StringUtils.isEmpty(md.getDoi())) {
                DoiReservation reservation = getReservedDoi();
                if (null==reservation)
                    throw new IOException ("DOI reservation failure.");
                // set it
                md.setDoi(reservation.getReservedDoi());
            }

            // persist this to the database
            store(em, md, user);

            // re-attach metadata to transaction in order to store any changes beyond this point
            md = em.find(DOECodeMetadata.class, md.getCodeId());

            // if there's a FILE associated here, store it
            String fullFileName = "";
            if ( null!=file && null!=fileInfo ) {
                try {
                    fullFileName = writeFile(file, md.getCodeId(), fileInfo.getFileName(), FILE_UPLOADS);
                    md.setFileName(fullFileName);
                } catch ( IOException e ) {
                    log.error ("File Upload Failed: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("File upload failed.")
                            .build();
                }
            }

            // if there's a CONTAINER IMAGE associated here, store it
            String fullContainerName = "";
            if ( null!=container && null!=containerInfo ) {
                try {
                    fullContainerName = writeFile(container, md.getCodeId(), containerInfo.getFileName(), CONTAINER_UPLOADS);
                    md.setContainerName(fullContainerName);
                } catch ( IOException e ) {
                    log.error ("Container Image Upload Failed: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("Container Image upload failed.")
                            .build();
                }
            }

            // check validations
            List<String> errors = validateAnnounce(md);
            if ( !errors.isEmpty() ) {
                return ErrorResponse
                        .badRequest(errors)
                        .build();
            }

            // create OSTI Hosted project, as needed
            try {
                // process local GitLab, if needed
                processOSTIGitLab(md);
            } catch ( Exception e ) {
                log.error("OSTI GitLab failure: " + e.getMessage());
                return ErrorResponse
                        .internalServerError("Unable to create OSTI Hosted project: " + e.getMessage())
                        .build();
            }

            // send this file upload along to archiver if configured
            try {
                // if no file/container, but previously Saved with a file/container, we need to attach to those streams and send to Archiver
                if (previouslySaved) {
                    if (null==file && !StringUtils.isBlank(md.getFileName())) {
                        java.nio.file.Path destination = Paths.get(FILE_UPLOADS, String.valueOf(md.getCodeId()), md.getFileName());
                        fullFileName = destination.toString();
                        file = Files.newInputStream(destination);
                    }
                    if (null==container && !StringUtils.isBlank(md.getContainerName())) {
                        java.nio.file.Path destination = Paths.get(CONTAINER_UPLOADS, String.valueOf(md.getCodeId()), md.getContainerName());
                        fullContainerName = destination.toString();
                        container = Files.newInputStream(destination);
                    }
                }

                // if a FILE or CONTAINER was sent, create a File Object from it
                File archiveFile = (null==file) ? null : new File(fullFileName);
                File archiveContainer = null; //(null==container) ? null : new File(fullContainerName);
                if (DOECodeMetadata.Accessibility.CO.equals(md.getAccessibility()))
                    // if CO project type, no need to archive the repo because it is local GitLab
                    sendToArchiver(md.getCodeId(), null, archiveFile, archiveContainer);
                else
                    sendToArchiver(md.getCodeId(), md.getRepositoryLink(), archiveFile, archiveContainer);
            } catch ( IOException e ) {
                log.error("Archiver call failure: " + e.getMessage());
                return ErrorResponse
                        .internalServerError("Unable to archive project.")
                        .build();
            }

            // send any updates to DataCite as well (if RELEASE DATE is set)
            if (StringUtils.isNotEmpty(md.getDoi()) && null!=md.getReleaseDate()) {
                try {
                    DataCite.register(md);
                } catch ( IOException e ) {
                    // if DataCite registration failed, say why
                    log.warn("DataCite ERROR: " + e.getMessage());
                    return ErrorResponse
                            .internalServerError("The DOI registration service is currently unavailable, please try to submit your record later. If the issue persists, please contact doecode@osti.gov.")
                            .build();
                }
            }
            // store the snapshot copy of Metadata in SPECIAL STATUS
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.getSnapshotKey().setCodeId(md.getCodeId());
            snapshot.getSnapshotKey().setSnapshotStatus(md.getWorkflowStatus());
            snapshot.setDoi(md.getDoi());
            snapshot.setDoiIsMinted(md.getReleaseDate() != null);
            snapshot.setJson(md.toJson().toString());

            em.merge(snapshot);

            // if we make it this far, go ahead and commit the transaction
            em.getTransaction().commit();

            // send NOTIFICATION if configured
            sendStatusNotification(md);

            // and we're happy
            return Response
                    .ok()
                    .entity(mapper.createObjectNode().putPOJO("metadata", md.toJson()).toString())
                    .build();
        } catch ( BadRequestException e ) {
            return e.getResponse();
        } catch ( NotFoundException e ) {
            return ErrorResponse
                    .notFound(e.getMessage())
                    .build();
        } catch ( IllegalAccessException e ) {
            log.warn("Persistence Error: Invalid owner update attempt: " + user.getEmail());
            log.warn("Message: " + e.getMessage());
            return ErrorResponse
                    .forbidden("Invalid Access: Unable to edit indicated record.")
                    .build();
        } catch ( ValidationException e ) {
            log.warn("Validation Error: " + e.getMessage());
            return ErrorResponse
                    .badRequest(e.getMessage())
                    .build();
        } catch ( IOException | InvocationTargetException e ) {
            if ( em.getTransaction().isActive())
                em.getTransaction().rollback();

            log.warn("Persistence Error: " + e.getMessage());
            return ErrorResponse
                    .internalServerError("IO Error announcing record.")
                    .build();
        } finally {
            em.close();
        }
    }

    /**
     * Support multipart-file upload POSTs to SUBMIT.
     *
     * Response Codes:
     * 200 - OK, JSON returned of the metadata information
     * 400 - validation error, errors returned in JSON
     * 401 - authentication is required to POST
     * 403 - access is forbidden to this record
     * 500 - file upload or database operation failed
     *
     * @param metadata contains the JSON of the record metadata information
     * @param file the uploaded file to attach
     * @param fileInfo disposition information for the file name
     * @param container the uploaded container image to attach
     * @param containerInfo disposition information for the container image name
     * @return a Response appropriate to the request status
     */
    @POST
    @Consumes (MediaType.MULTIPART_FORM_DATA)
    @Produces (MediaType.APPLICATION_JSON)
    @Path ("/submit")
    @RequiresAuthentication
    public Response submitFile(@FormDataParam("metadata") String metadata,
            @FormDataParam("file") InputStream file,
            @FormDataParam("file") FormDataContentDisposition fileInfo,
            @FormDataParam("container") InputStream container,
            @FormDataParam("container") FormDataContentDisposition containerInfo) {
        return doSubmit(metadata, file, fileInfo, container, containerInfo);
    }


    /**
     * SUBMIT a record to DOE CODE.
     *
     * Will return a FORBIDDEN attempt should a User attempt to modify someone
     * else's record.
     *
     * @param object JSON of the DOECodeMetadata object to SUBMIT
     * @return a Response containing the persisted metadata entity in JSON
     * @throws InternalServerErrorException on JSON parsing or other IO errors
     */
    @POST
    @Consumes ( MediaType.APPLICATION_JSON )
    @Produces ( MediaType.APPLICATION_JSON )
    @Path ("/submit")
    @RequiresAuthentication
    public Response submit(String object) {
        return doSubmit(object, null, null, null, null);
    }

    /**
     * ANNOUNCE endpoint; saves Software record to DOE CODE and sends results to
     * OSTI ELINK and enters the OSTI workflow.
     *
     * Will return a FORBIDDEN response if the OWNER logged in does not match
     * the record's OWNER.
     *
     * @param object the JSON of the record to ANNOUNCE.
     * @return a Response containing the resulting JSON metadata sent to OSTI,
     * including any DOI registered.
     * @throws InternalServerErrorException on JSON parsing or other IO errors
     */
    @POST
    @Consumes ( MediaType.APPLICATION_JSON )
    @Produces ( MediaType.APPLICATION_JSON )
    @Path ("/announce")
    @RequiresAuthentication
    public Response announce(String object) {
        return doAnnounce(object, null, null, null, null);
    }

    /**
     * Perform ANNOUNCE workflow with associated file upload.
     *
     * Response Codes:
     * 200 - OK, response includes metadata JSON
     * 400 - record validation failed, errors in JSON
     * 401 - Authentication is required to POST
     * 403 - Access is forbidden to this record
     * 500 - JSON parsing error or other unhandled exception
     *
     * @param metadata the METADATA to ANNOUNCE (send to OSTI)
     * @param file a FILE to associate with this METADATA
     * @param fileInfo file disposition information for the FILE
     * @param container a CONTAINER IMAGE to associate with this METADATA
     * @param containerInfo file disposition information for the CONTAINER IMAGE
     * @return a Response containing the metadata, or error information
     */
    @POST
    @Consumes (MediaType.MULTIPART_FORM_DATA)
    @Produces (MediaType.APPLICATION_JSON)
    @Path ("/announce")
    @RequiresAuthentication
    public Response announceFile(@FormDataParam("metadata") String metadata,
            @FormDataParam("file") InputStream file,
            @FormDataParam("file") FormDataContentDisposition fileInfo,
            @FormDataParam("container") InputStream container,
            @FormDataParam("container") FormDataContentDisposition containerInfo) {
        return doAnnounce(metadata, file, fileInfo, container, containerInfo);
    }

    /**
     * POST a Metadata JSON object to the persistence layer.
     * Saves the object to persistence layer; if the entity is already Submitted,
     * this operation is invalid.
     *
     * @param object the JSON to post
     * @return the JSON after persistence; perhaps containing assigned codeId, etc.
     */
    @POST
    @Consumes ( MediaType.APPLICATION_JSON )
    @Produces ( MediaType.APPLICATION_JSON )
    @RequiresAuthentication
    @Path ("/save")
    public Response save(String object) {
        return doSave(object, null, null, null, null);
    }

    /**
     * POST a Metadata to be SAVED with a file upload.
     *
     * @param metadata the JSON containing the Metadata information
     * @param file a FILE associated with this record
     * @param fileInfo file disposition information for the FILE
     * @param container a CONTAINER IMAGE associated with this record
     * @param containerInfo file disposition information for the CONTAINER IMAGE
     * @return a Response containing the JSON of the metadata if successful, or
     * error information if not
     */
    @POST
    @Consumes (MediaType.MULTIPART_FORM_DATA)
    @Produces (MediaType.APPLICATION_JSON)
    @RequiresAuthentication
    @Path ("/save")
    public Response save(@FormDataParam("metadata") String metadata,
            @FormDataParam("file") InputStream file,
            @FormDataParam("file") FormDataContentDisposition fileInfo,
            @FormDataParam("container") InputStream container,
            @FormDataParam("container") FormDataContentDisposition containerInfo) {
        return doSave(metadata, file, fileInfo, container, containerInfo);
    }

    @GET
    @Produces (MediaType.APPLICATION_JSON)
    @Path ("/reindex")
    @RequiresAuthentication
    @RequiresRoles ("ContentAdmin")
    public Response reindex() throws IOException {
        EntityManager em = DoeServletContextListener.createEntityManager();

        try {
            TypedQuery<MetadataSnapshot> query = em.createNamedQuery("MetadataSnapshot.findAllByStatus", MetadataSnapshot.class)
                    .setParameter("status", DOECodeMetadata.Status.Approved);
            List<MetadataSnapshot> results = query.getResultList();
            int records = 0;

            for ( MetadataSnapshot amd : results ) {
                DOECodeMetadata md = DOECodeMetadata.parseJson(new StringReader(amd.getJson()));

                sendToIndex(em, md);
                ++records;
            }

            return Response
                    .ok()
                    .entity(mapper.createObjectNode().put("indexed", String.valueOf(records)).toString())
                    .build();
        } finally {
            em.close();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/refresh")
    @RequiresAuthentication
    @RequiresRoles("ContentAdmin")
    public Response refresh() throws Exception {
        try {
            DoeServletContextListener.refreshCaches();

            return Response
                    .ok()
                    .entity(mapper.createObjectNode().put("refreshed", "true").toString())
                    .build();
        } catch (Exception e) {
            log.warn("Refresh Error: " + e.getMessage());
            return ErrorResponse
                    .internalServerError("Error refreshing caches.")
                    .build();
        }
    }

    /**
     * APPROVE endpoint; sends the Metadata of a targeted project to Index.
     *
     * Will return a FORBIDDEN response if the OWNER logged in does not match
     * the record's OWNER.
     *
     * @param codeId the CODE ID of the record to APPROVE.
     * @return a Response containing the JSON of the approved record if successful, or
     * error information if not
     * @throws InternalServerErrorException on JSON parsing or other IO errors
     */
    @GET
    @Path ("/approve/{codeId}")
    @Produces (MediaType.APPLICATION_JSON)
    @RequiresAuthentication
    @RequiresRoles("ApprovalAdmin")
    public Response approve(@PathParam("codeId") Long codeId) {
        EntityManager em = DoeServletContextListener.createEntityManager();
        Subject subject = SecurityUtils.getSubject();
        User user = (User) subject.getPrincipal();

        try {
            DOECodeMetadata md = em.find(DOECodeMetadata.class, codeId);
            em.detach(md);

            if ( null==md )
                return ErrorResponse
                        .notFound("Code ID not on file.")
                        .build();

            // make sure this is Submitted or Announced
            if (!DOECodeMetadata.Status.Submitted.equals(md.getWorkflowStatus()) && !DOECodeMetadata.Status.Announced.equals(md.getWorkflowStatus()))
                return ErrorResponse
                        .badRequest("Metadata is not in the Submitted/Announced workflow state.")
                        .build();

            // move Approved Container to downloadable path
            try {
                approveContainerUpload(md);
            } catch ( IOException e ) {
                log.error("Container move failure: " + e.getMessage());
                return ErrorResponse
                        .internalServerError(e.getMessage())
                        .build();
            }

            // if approving announced, send this to OSTI
            if (DOECodeMetadata.Status.Announced.equals(md.getWorkflowStatus())) {
                sendToOsti(em, md);
            }

            em.getTransaction().begin();
            // set the WORKFLOW STATUS
            md.setWorkflowStatus(Status.Approved);

            // persist this to the database, as validations should already be complete at this stage.
            store(em, md, user);

            // prior to updating snapshot, gather RI List for backfilling
            List<RelatedIdentifier> previousRiList = getPreviousRiList(em, md);

            // store the snapshot copy of Metadata
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.getSnapshotKey().setCodeId(md.getCodeId());
            snapshot.getSnapshotKey().setSnapshotStatus(md.getWorkflowStatus());
            snapshot.setDoi(md.getDoi());
            snapshot.setDoiIsMinted(md.getReleaseDate() != null);
            snapshot.setJson(md.toJson().toString());

            em.merge(snapshot);

            // perform RI backfilling
            backfillProjects(em, md, previousRiList);

            // if we make it this far, go ahead and commit the transaction
            em.getTransaction().commit();

            // send it to the indexer
            sendToIndex(em, md);

            // send APPROVAL NOTIFICATION to OWNER
            sendApprovalNotification(md);
            sendPOCNotification(md);

            // and we're happy
            return Response
                    .status(Response.Status.OK)
                    .entity(mapper.createObjectNode().putPOJO("metadata", md.toJson()).toString())
                    .build();
        } catch ( BadRequestException e ) {
            return e.getResponse();
        } catch ( NotFoundException e ) {
            return ErrorResponse
                    .status(Response.Status.NOT_FOUND, e.getMessage())
                    .build();
        } catch ( IllegalAccessException e ) {
            log.warn("Persistence Error: Invalid owner update attempt: " + user.getEmail());
            log.warn("Message: " + e.getMessage());
            return ErrorResponse
                    .status(Response.Status.FORBIDDEN, "Invalid Access:  Unable to edit indicated record.")
                    .build();
        } catch ( IOException | InvocationTargetException e ) {
            if ( em.getTransaction().isActive())
                em.getTransaction().rollback();

            log.warn("Persistence Error: " + e.getMessage());
            return ErrorResponse
                    .status(Response.Status.INTERNAL_SERVER_ERROR, "IO Error approving record: " + e.getMessage())
                    .build();
        } finally {
            em.close();
        }
    }

    /**
     * Send metadata JSON to OSTI.
     *
     * @param em the related EntityManager
     * @param md the Metadata to send to OSTI
     */
    private void sendToOsti(EntityManager em, DOECodeMetadata md) throws IOException {
        // if configured, post this to OSTI
        String publishing_host = context.getInitParameter("publishing.host");
        if (null!=publishing_host) {
            // do not index DOE CODE New/Previous DOI related identifiers if Approved without a Release Date
            DOECodeMetadata indexableMd = removeNonIndexableRi(em, md);

            // set some reasonable default timeouts
            // create an HTTP client to request through
            try (CloseableHttpClient hc =
                    HttpClientBuilder
                    .create()
                    .setDefaultRequestConfig(RequestConfig
                            .custom()
                            .setSocketTimeout(60000)
                            .setConnectTimeout(60000)
                            .setConnectionRequestTimeout(60000)
                            .build())
                    .build()) {
            HttpPost post = new HttpPost(publishing_host + "/services/softwarecenter?action=api");
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Accept", "application/json");
            post.setEntity(new StringEntity(mapper.writeValueAsString(indexableMd), "UTF-8"));

                HttpResponse response = hc.execute(post);
                String text = EntityUtils.toString(response.getEntity());

                if ( HttpStatus.SC_OK!=response.getStatusLine().getStatusCode()) {
                    log.warn("OSTI Error: " + text);
                    throw new IOException ("OSTI software publication error for " + md.getCodeId());
                }
            }
        }
    }

    /**
     * Remove duplicate RI entries and normalize values.
     *
     * @param md the Metadata to evaluate
     */
    private void normalizeRelatedIdentifiers(DOECodeMetadata md) {
        List<RelatedIdentifier> currentList = md.getRelatedIdentifiers();

        // nothing to process
        if (currentList == null || currentList.isEmpty())
            return;

        // trim DOI and URL values
        for(RelatedIdentifier ri : currentList)
            if (RelatedIdentifier.Type.DOI.equals(ri.getIdentifierType()))
                ri.setIdentifierValue(trimDoi(ri.getIdentifierValue()));
            else if (RelatedIdentifier.Type.URL.equals(ri.getIdentifierType()))
                ri.setIdentifierValue(trimUrl(ri.getIdentifierValue()));

        // remove RI duplicates
        Set<RelatedIdentifier> s = new HashSet<>();
        s.addAll(currentList);
        currentList.clear();
        currentList.addAll(s);
        md.setRelatedIdentifiers(currentList);
    }

    /**
     * Trim away unneeded DOI prefixes, etc.
     *
     * @param doi the DOI to trim
     */
    private String trimDoi(String doi) {
        // trim DOI down to 10.* variation
        if (!StringUtils.isBlank(doi)) {
            doi = doi.trim();
            Matcher m = DOI_TRIM_PATTERN.matcher(doi);
            if (m.find())
                doi = m.group(1);
        }
        return doi;
    }

    /**
     * Trim away unneeded URL characters, etc.
     *
     * @param url the URL to trim
     */
    private String trimUrl(String url) {
        // remove extra spaces and single trailing slash, if exist
        if (!StringUtils.isBlank(url)) {
            url = url.trim();
            Matcher m = URL_TRIM_PATTERN.matcher(url);
            if (m.find())
                url = m.group(1);
        }
        return url;
    }

    /**
     * Normalize metadata information.
     *
     * @param md the Metadata to evaluate
     */
    private void normalizeMetadata(DOECodeMetadata md) {
        // trim main DOI
        md.setDoi(trimDoi(md.getDoi()));

        // trim main URLs
        md.setRepositoryLink(trimUrl(md.getRepositoryLink()));
        md.setLandingPage(trimUrl(md.getLandingPage()));
        md.setProprietaryUrl(trimUrl(md.getProprietaryUrl()));
        md.setDocumentationUrl(trimUrl(md.getDocumentationUrl()));
    }

    /**
     * Normalize certain aspects of the data prior to storage.
     *
     * @param md the Metadata to normalize
     */
    private void performDataNormalization(DOECodeMetadata md) {
        normalizeMetadata(md);
        normalizeRelatedIdentifiers(md);
    }

    /**
     * Store a File to a specific directory location. All files associated with
     * a CODEID are stored in the same folder.
     *
     * @param in the InputStream containing the file content
     * @param codeId the CODE ID associated with this file content
     * @param fileName the base file name of the file
     * @param basePath the base path destination for the file content
     * @return the absolute filesystem path to the file
     * @throws IOException on IO errors
     */
    private static String writeFile(InputStream in, Long codeId, String fileName, String basePath) throws IOException {
        // store this file in a designated base path
        java.nio.file.Path destination =
                Paths.get(basePath, String.valueOf(codeId), fileName);
        // make intervening folders if needed
        Files.createDirectories(destination.getParent());
        // save it (CLOBBER existing, if one there)
        Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);

        return destination.toString();
    }

    /**
     * Convert a File InputStream to a Base64 string.
     *
     * @param in the InputStream containing the file content
     * @return the Base64 string of the file
     * @throws IOException on IO errors
     */
    private static String convertBase64(InputStream in) throws IOException {
        Base64InputStream b64in = new Base64InputStream(in, true);
        return IOUtils.toString(b64in, "UTF-8");
    }

    /**
     * Perform validations for SUBMITTED records.
     *
     * @param m the Metadata information to validate
     * @return a List of error messages if any validation errors, empty if none
     */
    protected static List<String> validateSubmit(DOECodeMetadata m) {
        List<String> reasons = new ArrayList<>();
        if (null==m.getAccessibility())
            reasons.add("Missing Source Accessibility.");
        if (StringUtils.isBlank(m.getSoftwareTitle()))
            reasons.add("Software title is required.");
        if (StringUtils.isBlank(m.getDescription()))
            reasons.add("Description is required.");
        if (null==m.getLicenses())
            reasons.add("A License is required.");
        else if (m.getLicenses().contains(DOECodeMetadata.License.Other.value()) && StringUtils.isBlank(m.getProprietaryUrl()))
            reasons.add("Proprietary License URL is required.");
        if (null==m.getDevelopers() || m.getDevelopers().isEmpty())
            reasons.add("At least one developer is required.");
        else {
            for ( Developer developer : m.getDevelopers() ) {
                if ( StringUtils.isBlank(developer.getFirstName()) )
                    reasons.add("Developer missing first name.");
                if ( StringUtils.isBlank(developer.getLastName()) )
                    reasons.add("Developer missing last name.");
                if ( StringUtils.isNotBlank(developer.getEmail()) ) {
                    if (!Validation.isValidEmail(developer.getEmail()))
                        reasons.add("Developer email \"" + developer.getEmail() +"\" is not valid.");
                }
                if ( StringUtils.isNotBlank(developer.getOrcid()) ) {
                    if (!Validation.isValidORCID(developer.getOrcid()))
                        reasons.add("Developer ORCID \"" + developer.getOrcid() +"\" is not valid.");
                }
            }
        }
        if (!(null==m.getContributors() || m.getContributors().isEmpty())) {
            for ( Contributor contributor : m.getContributors() ) {
                if ( StringUtils.isNotBlank(contributor.getOrcid()) ) {
                    if (!Validation.isValidORCID(contributor.getOrcid()))
                        reasons.add("Contributor ORCID \"" + contributor.getOrcid() +"\" is not valid.");
                }
                if (StringUtils.isBlank(contributor.getFirstName()) || StringUtils.isBlank(contributor.getLastName()) || (null == contributor.getContributorType())) {
                    reasons.add("Contributor must include first name, last name, and contributor type.");
                }
            }
        }
        // if "OS" accessibility, a REPOSITORY LINK is REQUIRED
        if (DOECodeMetadata.Accessibility.OS.equals(m.getAccessibility())) {
            if (StringUtils.isBlank(m.getRepositoryLink()))
                reasons.add("Repository URL is required for open source submissions.");
        } else if (!DOECodeMetadata.Accessibility.CO.equals(m.getAccessibility())) {
            // non-OS & non-CO submissions require a LANDING PAGE (prefix with http:// if missing)
            if (!Validation.isValidUrl(m.getLandingPage()))
                reasons.add("A valid Landing Page URL is required for non-open source submissions.");
        }
        // if repository link is present, and not CO, it needs to be valid too
        if (StringUtils.isNotBlank(m.getRepositoryLink()) && !DOECodeMetadata.Accessibility.CO.equals(m.getAccessibility()) && !GitHub.isTagReferenceAndValid(m.getRepositoryLink()) && !Validation.isValidRepositoryLink(m.getRepositoryLink()))
            reasons.add("Repository URL is not a valid repository.");

        return reasons;
    }

    /**
     * Perform ANNOUNCE validations on metadata.
     *
     * @param m the Metadata to check
     * @return a List of announcement validation errors, empty if none
     */
    protected static List<String> validateAnnounce(DOECodeMetadata m) {
        List<String> reasons = new ArrayList<>();
        // get all the SUBMITTED reasons, if any
        reasons.addAll(validateSubmit(m));
        // add ANNOUNCE-specific validations
        if (null==m.getReleaseDate())
            reasons.add("Release date is required.");

        reasons.addAll(validateSponsorOrgs(m));

        if (null==m.getResearchOrganizations() || m.getResearchOrganizations().isEmpty())
            reasons.add("At least one research organization is required.");
        else {
            for ( ResearchOrganization o : m.getResearchOrganizations() ) {
                if (StringUtils.isBlank(o.getOrganizationName()))
                    reasons.add("Research organization name is required.");
            }
        }
        if (StringUtils.isBlank(m.getRecipientName()))
            reasons.add("Contact name is required.");
        if (StringUtils.isBlank(m.getRecipientEmail()))
            reasons.add("Contact email is required.");
        else {
            if (!Validation.isValidEmail(m.getRecipientEmail()))
                reasons.add("Contact email is not valid.");
        }
        if (StringUtils.isBlank(m.getRecipientPhone()))
            reasons.add("Contact phone number is required.");
        else {
            if (!Validation.isValidPhoneNumber(m.getRecipientPhone()))
                reasons.add("Contact phone number is not valid.");
        }
        if (StringUtils.isBlank(m.getRecipientOrg()))
            reasons.add("Contact organization is required.");

        if (!DOECodeMetadata.Accessibility.OS.equals(m.getAccessibility()))
            if (StringUtils.isBlank(m.getFileName()))
                reasons.add("A file archive must be included for non-open source submissions.");

        return reasons;
    }

    /**
     * Check SPONSOR ORG validations on metadata.
     *
     * @param m the Metadata to check
     * @return a List of sponsor org validation errors, empty if none
     */
    private static List<String> validateSponsorOrgs(DOECodeMetadata m) {
        List<String> reasons = new ArrayList<>();

        int doeSponsorCount = 0;
        if (null==m.getSponsoringOrganizations() || m.getSponsoringOrganizations().isEmpty())
            reasons.add("At least one sponsoring organization is required.");
        else {
            for ( SponsoringOrganization o : m.getSponsoringOrganizations() ) {
                if (StringUtils.isBlank(o.getOrganizationName()))
                    reasons.add("Sponsoring organization name is required.");
                if (StringUtils.isBlank(o.getPrimaryAward()) && o.isDOE())
                    reasons.add("Primary award number is required.");
                else if (o.isDOE() && !Validation.isValidAwardNumber(o.getPrimaryAward()))
                    reasons.add("Award Number " + o.getPrimaryAward() + " is not valid.");

                if (o.isDOE())
                    doeSponsorCount++;
            }
        }

        if (doeSponsorCount == 0)
            reasons.add("At least one DOE funded sponsoring organization is required.");

        return reasons;
    }

    /**
     * Send a NOTIFICATION EMAIL (if configured) when a record is SUBMITTED or
     * ANNOUNCED.
     *
     * @param md the METADATA in question
     */
    private static void sendStatusNotification(DOECodeMetadata md) {
        HtmlEmail email = new HtmlEmail();
        email.setCharset(org.apache.commons.mail.EmailConstants.UTF_8);
        email.setHostName(EMAIL_HOST);

        // if EMAIL or DESTINATION ADDRESS are not set, abort
        if (StringUtils.isEmpty(EMAIL_HOST) ||
            StringUtils.isEmpty(EMAIL_FROM) ||
            StringUtils.isEmpty(EMAIL_SUBMISSION))
            return;

        // only applicable to SUBMITTED or ANNOUNCED records
        if (!Status.Announced.equals(md.getWorkflowStatus()) &&
            !Status.Submitted.equals(md.getWorkflowStatus()))
            return;

        // get the SITE information
        String siteCode = md.getSiteOwnershipCode();
        Site site = SiteServices.findSiteBySiteCode(siteCode);
        if (null == site) {
            log.warn("Unable to locate SITE information for SITE CODE: " + siteCode);
            return;
        }
        String lab = site.getLab();
        lab = lab.isEmpty() ? siteCode : lab;

        try {
            email.setFrom(EMAIL_FROM);
            email.setSubject("DOE CODE Record " + md.getWorkflowStatus().toString());
            email.addTo(EMAIL_SUBMISSION);

            String softwareTitle = md.getSoftwareTitle().replaceAll("^\\h+|\\h+$","");

            StringBuilder msg = new StringBuilder();
            msg.append("<html>");
            msg.append("A new DOE CODE record has been ")
               .append(md.getWorkflowStatus())
               .append(" for ")
               .append(lab)
               .append(" and is awaiting approval:");

            msg.append("<P>Code ID: ").append(md.getCodeId());
            msg.append("<BR>Software Title: ").append(softwareTitle);
            msg.append("</html>");

            email.setHtmlMsg(msg.toString());

            email.send();
        } catch ( EmailException e ) {
            log.error("Failed to send submission/announcement notification message for #" + md.getCodeId());
            log.error("Message: " + e.getMessage());
        }
    }

    /**
     * Send an email notification on APPROVAL of DOE CODE records.
     *
     * @param md the METADATA to send notification for
     */
    private static void sendApprovalNotification(DOECodeMetadata md) {
        HtmlEmail email = new HtmlEmail();
        email.setCharset(org.apache.commons.mail.EmailConstants.UTF_8);
        email.setHostName(EMAIL_HOST);

        // if HOST or record OWNER or PROJECT MANAGER NAME isn't set, cannot send
        if (StringUtils.isEmpty(EMAIL_HOST) ||
            StringUtils.isEmpty(EMAIL_FROM) ||
            null==md ||
            StringUtils.isEmpty(md.getOwner()) ||
            StringUtils.isEmpty(PM_NAME))
            return;
        // only has meaning for APPROVED records
        if (!Status.Approved.equals(md.getWorkflowStatus()))
            return;

        try {
            // get the OWNER information
            User owner = UserServices.findUserByEmail(md.getOwner());
            if (null==owner) {
                log.warn("Unable to locate USER information for Code ID: " + md.getCodeId());
                return;
            }

            Long codeId = md.getCodeId();

            // lookup previous Snapshot status info for item
            EntityManager em = DoeServletContextListener.createEntityManager();
            TypedQuery<MetadataSnapshot> querySnapshot = em.createNamedQuery("MetadataSnapshot.findByCodeIdLastNotStatus", MetadataSnapshot.class)
                    .setParameter("status", DOECodeMetadata.Status.Approved)
                    .setParameter("codeId", codeId);

            String lastApprovalFor = "submitted/announced";
            List<MetadataSnapshot> results = querySnapshot.setMaxResults(1).getResultList();
            for ( MetadataSnapshot ms : results ) {
                lastApprovalFor = ms.getSnapshotKey().getSnapshotStatus().toString().toLowerCase();
            }

            String softwareTitle = md.getSoftwareTitle().replaceAll("^\\h+|\\h+$","");

            email.setFrom(EMAIL_FROM);
            email.setSubject("Approved -- DOE CODE ID: " + codeId + ", " + softwareTitle);
            email.addTo(md.getOwner());

            // if email is provided, BCC the Project Manager
            if (!StringUtils.isEmpty(PM_EMAIL))
                email.addBcc(PM_EMAIL, PM_NAME);

            StringBuilder msg = new StringBuilder();

            msg.append("<html>");
            msg.append("Dear ")
               .append(owner.getFirstName())
               .append(" ")
               .append(owner.getLastName())
               .append(":");

            msg.append("<P>Thank you -- your ").append(lastApprovalFor).append(" project, DOE CODE ID: <a href=\"")
               .append(SITE_URL)
               .append("/biblio/")
               .append(codeId)
               .append("\">")
               .append(codeId)
               .append("</a>, has been approved.  It is now <a href=\"")
               .append(SITE_URL)
               .append("\">searchable</a> in DOE CODE by, for example, title or CODE ID #.</P>");

            // OMIT the following for BUSINESS TYPE software, or last ANNOUNCED software
            if (!DOECodeMetadata.Type.B.equals(md.getSoftwareType()) && !lastApprovalFor.equalsIgnoreCase("announced")) {
                msg.append("<P>You may need to continue editing your project to announce it to the Department of Energy ")
                   .append("to ensure announcement and dissemination in accordance with DOE statutory responsibilities. For more information please see ")
                   .append("<a href=\"")
                   .append(SITE_URL)
                   .append("/faq#what-does-it-mean-to-announce\">What does it mean to announce scientific code to DOE CODE?</a></P>");
            }
            msg.append("<P>If you have questions such as What are the benefits of getting a DOI for code or software?, see the ")
               .append("<a href=\"")
               .append(SITE_URL)
               .append("/faq\">DOE CODE FAQs</a>.</P>");
            msg.append("<P>If we can be of assistance, please do not hesitate to <a href=\"mailto:doecode@osti.gov\">Contact Us</a>.</P>");
            msg.append("<P>Sincerely,</P>");
            msg.append("<P>").append(PM_NAME).append("<BR/>Product Manager for DOE CODE<BR/>USDOE/OSTI</P>");

            msg.append("</html>");

            email.setHtmlMsg(msg.toString());

            email.send();
        } catch ( EmailException e ) {
            log.error("Unable to send APPROVAL notification for #" + md.getCodeId());
            log.error("Message: " + e.getMessage());
        }
    }

    /**
     * Send a POC email notification on SUBMISSION/APPROVAL of DOE CODE records.
     *
     * @param md the METADATA to send notification for
     */
    private static void sendPOCNotification(DOECodeMetadata md) {
        // if HOST or MD or PROJECT MANAGER NAME isn't set, cannot send
        if (StringUtils.isEmpty(EMAIL_HOST) ||
            StringUtils.isEmpty(EMAIL_FROM) ||
            null == md ||
            StringUtils.isEmpty(PM_NAME))
            return;

        Long codeId = md.getCodeId();
        String siteCode = md.getSiteOwnershipCode();
        Status workflowStatus = md.getWorkflowStatus();

        // if SITE OWNERSHIP isn't set, cannot send
        if (StringUtils.isEmpty(siteCode))
            return;

        // only applicable to APPROVED records
        if (!Status.Approved.equals(workflowStatus))
            return;

        // get the SITE information
        Site site = SiteServices.findSiteBySiteCode(siteCode);
        if (null == site) {
            log.warn("Unable to locate SITE information for SITE CODE: " + siteCode);
            return;
        }

        // lookup previous Snapshot status info for item
        EntityManager em = DoeServletContextListener.createEntityManager();
        TypedQuery<MetadataSnapshot> querySnapshot = em.createNamedQuery("MetadataSnapshot.findByCodeIdLastNotStatus", MetadataSnapshot.class)
                .setParameter("status", DOECodeMetadata.Status.Approved)
                .setParameter("codeId", codeId);

        String lastApprovalFor = "submitted/announced";
        List<MetadataSnapshot> results = querySnapshot.setMaxResults(1).getResultList();
        for ( MetadataSnapshot ms : results ) {
            lastApprovalFor = ms.getSnapshotKey().getSnapshotStatus().toString().toLowerCase();
        }

        List<String> emails = site.getPocEmails();

        // if POC is setup
        if (emails != null && !emails.isEmpty()) {
            try {
                HtmlEmail email = new HtmlEmail();
                email.setCharset(org.apache.commons.mail.EmailConstants.UTF_8);
                email.setHostName(EMAIL_HOST);

                String lab = site.getLab();
                lab = lab.isEmpty() ? siteCode : lab;

                String softwareTitle = md.getSoftwareTitle().replaceAll("^\\h+|\\h+$","");

                email.setFrom(EMAIL_FROM);
                email.setSubject("POC Notification -- " + workflowStatus + " -- DOE CODE ID: " + codeId + ", " + softwareTitle);

                for (String pocEmail : emails)
                    email.addTo(pocEmail);

                // if email is provided, BCC the Project Manager
                if (!StringUtils.isEmpty(PM_EMAIL))
                    email.addBcc(PM_EMAIL, PM_NAME);

                StringBuilder msg = new StringBuilder();

                msg.append("<html>");
                msg.append("Dear Sir or Madam:");

                String biblioLink = SITE_URL + "/biblio/" + codeId;

                msg.append("<p>As a point of contact for ").append(lab).append(", we wanted to inform you that a software project, titled ")
                   .append(softwareTitle)
                   .append(", associated with your organization was ").append(lastApprovalFor).append(" to DOE CODE and assigned DOE CODE ID: ")
                   .append(codeId)
                   .append(".  This project record is discoverable in <a href=\"")
                   .append(SITE_URL)
                   .append("\">DOE CODE</a>, e.g. searching by the project title or DOE CODE ID #, and can be found here: <a href=\"")
                   .append(biblioLink)
                   .append("\">")
                   .append(biblioLink)
                   .append("</a></p>");


                msg.append("<p>If you have any questions, please do not hesitate to <a href=\"mailto:doecode@osti.gov\">Contact Us</a>.</p>");
                msg.append("<p>Sincerely,</p>");
                msg.append("<p>").append(PM_NAME).append("<br/>Product Manager for DOE CODE<br/>USDOE/OSTI</p>");

                msg.append("</html>");

                email.setHtmlMsg(msg.toString());

                email.send();
            } catch ( EmailException e ) {
                log.error("Unable to send POC notification to " + Arrays.toString(emails.toArray()) + " for #" + md.getCodeId());
                log.error("Message: " + e.getMessage());
            }
        }
    }

    /**
     * As needed, Create/Update OSTI Hosted GitLab projects.
     *
     * @param md the METADATA to process GitLab for
     */
    private static void processOSTIGitLab(DOECodeMetadata md) throws Exception {
        try {
            // only process OSTI Hosted type
            if (!DOECodeMetadata.Accessibility.CO.equals(md.getAccessibility()))
                return;

            String fileName = md.getFileName();

            String codeId = String.valueOf(md.getCodeId());
            java.nio.file.Path uploadedFile = Paths.get(FILE_UPLOADS, String.valueOf(codeId), fileName);

            // if no file was uploaded, fail
            if (!Files.exists(uploadedFile))
                throw new Exception("File not found in Uploads directory! [" + uploadedFile.toString() + "]");

            // convert to base64 for GitLab
            String base64Content = convertBase64(new FileInputStream(uploadedFile.toString()));

            if (base64Content == null)
                throw new Exception("Base 64 Content required for OSTI Hosted project!");

            String projectName = "dc-" + md.getCodeId();
            String hostedFolder = "hosted_files";

            String uploadFile = hostedFolder + "/" + fileName;

            GitLabAPI glApi = new GitLabAPI();
            glApi.setProjectName(projectName);

            // check existance of project
            Project project = glApi.fetchProject();

            Commit commit = new Commit();
            if (project == null) {
                // create new project, if none exists
                project = glApi.createProject(md);

                commit.setBranch(glApi.getBranch());
                commit.setCommitMessage("Adding Hosted Files: " + fileName);
                commit.addBase64ActionByValues("create", uploadFile, base64Content);
            }
            else {
                // edit project, if one already exists
                project = glApi.updateProject(md);

                // for each file in the hosted folder, check against submitted files and process as needed
                GitLabFile[] files = glApi.fetchTree(hostedFolder);

                int adds = 0;
                int updates = 0;
                int deletes = 0;

                if (files != null) {
                    for ( GitLabFile f : files ) {
                        if (!f.getType().equalsIgnoreCase("tree")) {
                            if (f.getPath().equals(uploadFile)) {
                                // update, if filename exists already
                                commit.addBase64ActionByValues("update", uploadFile, base64Content);

                                updates++;
                            }
                            else {
                                // delete, if file is not being submitted
                                commit.addBase64ActionByValues("delete", f.getPath(), null);

                                deletes++;
                            }
                        }
                    }
                }

                // right now there is only ever one file, so if we did not updated anything, we need to create it
                if (updates == 0) {
                    // create, if there was no matching file to update
                    commit.addBase64ActionByValues("create", uploadFile, base64Content);

                    adds++;
                }

                String prefix;
                String detail = "\"" + fileName + "\"";
                if (adds == 1 && updates == 0 && deletes == 0) {
                    prefix = "Adding";
                }
                else if (adds == 0 && updates == 1 && deletes == 0) {
                    prefix = "Updating";
                }
                else {
                    prefix = "Modifying";

                    String tmp = (adds == 1) ? "\"" + fileName + "\"" : String.valueOf(adds);
                    detail = (adds > 0 ? "Added " + tmp : "");

                    tmp = (updates == 1) ? "\"" + fileName + "\"" : String.valueOf(updates);
                    detail += (updates > 0 ? (StringUtils.isBlank(detail) ? "" : ", ") + "Updated " + tmp : "");

                    detail += (deletes > 0 ? (StringUtils.isBlank(detail) ? "" : ", ") + "Deleted " + deletes : "");
                }

                commit.setBranch(project.getDefaultBranch());
                commit.setCommitMessage(prefix + " Hosted Files: " + detail);
            }

            // override repo link, no matter what
            if (!StringUtils.isBlank(project.getWebUrl()))
                md.setRepositoryLink(project.getWebUrl());
            else
                throw new Exception("Unable to determine GitLab Repository URL!");

            // commit file actions, as needed
            glApi.commitFiles(commit);

        } catch ( Exception e ) {
            // replace non-obvious 'name' with 'software_title' for user clarity
            String eMsg = e.getMessage();
            eMsg = eMsg.replaceFirst("'name'", "'software_title'");
            throw new Exception(eMsg);
        }
    }

    /**
     * As needed, move Container Uploads to download location.
     *
     * @param md the METADATA to process Container Approval for
     */
    private static void approveContainerUpload(DOECodeMetadata md) throws IOException {
        String containerName = md.getContainerName();

        // if nothing to move, return
        if (StringUtils.isBlank(containerName))
            return;

        String codeId = String.valueOf(md.getCodeId());
        java.nio.file.Path uploadedFile = Paths.get(CONTAINER_UPLOADS, String.valueOf(codeId), containerName);
        java.nio.file.Path approvedFile = Paths.get(CONTAINER_UPLOADS_APPROVED, String.valueOf(codeId), containerName);

        // if file already moved to approval, skip
        if (!Files.exists(uploadedFile) && Files.exists(approvedFile))
            return;

        // if file is missing, fail
        if (!Files.exists(uploadedFile))
            throw new IOException("Container not found in containers directory during Approval! [" + uploadedFile.toString() + "]");

        // make intervening folders if needed
        Files.createDirectories(approvedFile.getParent());

        try {
            Files.move(uploadedFile, approvedFile, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch ( IOException e ) {
            String eMsg = "Failed to move Container during Approval: " + e.getMessage();
            throw new IOException(eMsg);
        }
    }
}
