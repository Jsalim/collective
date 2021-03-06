package com.collective.recommender.runner;

import com.collective.analyzer.enrichers.EnrichmentService;
import com.collective.analyzer.enrichers.dbpedia.DBPediaAPI;
import com.collective.model.persistence.enhanced.WebResourceEnhanced;
import com.collective.model.profile.ProjectProfile;
import com.collective.model.profile.UserProfile;
import com.collective.permanentsearch.model.Search;
import com.collective.profiler.storage.ProfileStore;
import com.collective.profiler.storage.ProfileStoreConfiguration;
import com.collective.profiler.storage.ProfileStoreException;
import com.collective.profiler.storage.SesameVirtuosoProfileStore;
import com.collective.recommender.Recommender;
import com.collective.recommender.RecommenderException;
import com.collective.recommender.SesameVirtuosoRecommender;
import com.collective.recommender.categories.exceptions.CategoriesMappingStorageException;
import com.collective.recommender.categories.model.MappedResource;
import com.collective.recommender.categories.persistence.CategoriesMappingStorage;
import com.collective.recommender.categories.persistence.MybatisCategoriesMappingStorage;
import com.collective.recommender.configuration.*;
import com.collective.recommender.dynamicprofiler.ShortTermUserProfile;
import com.collective.recommender.dynamicprofiler.ShortTermUserProfileCalculator;
import com.collective.recommender.proxy.ranking.Ranker;
import com.collective.recommender.proxy.ranking.RankerException;
import com.collective.recommender.proxyimpl.ranking.WebResourceEnhancedRanker;
import com.collective.recommender.storage.KVSRecommendationsStorage;
import com.collective.recommender.storage.RecommendationsStorage;
import com.collective.recommender.storage.RecommendationsStorageException;
import com.collective.recommender.utils.UserIdParser;
import com.collective.recommender.utils.UserIdParserException;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import it.cybion.extractor.ContentExtractor;
import org.apache.commons.cli.*;
import org.apache.log4j.Logger;
import org.nnsoft.be3.Be3;
import org.nnsoft.be3.DefaultTypedBe3Impl;
import org.nnsoft.be3.typehandler.*;
import org.openrdf.model.Resource;
import org.openrdf.model.vocabulary.XMLSchema;
import tv.notube.commons.storage.alog.DefaultActivityLogImpl;
import tv.notube.commons.storage.model.ActivityLog;
import tv.notube.commons.storage.model.fields.Field;
import tv.notube.commons.storage.model.fields.IntegerField;
import tv.notube.commons.storage.model.fields.StringField;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 *
 */
/*TODO: (high) refactor xml nodes names since they are unclear and there could be a duplicate section */
public class Runner {

    private static final Logger LOGGER = Logger.getLogger(Runner.class);

    private static ProfileStore profileStore;

    private static Recommender recommender;

    private static RecommendationsStorage recommendationsStorage;

    private static ConfigurationManager configurationManager;

    private static MyBatisPermanentSearchStorage permanentSearchStorage;

    private static Long permanentSearchesLimit = 10L;

    private static ActivityLog activityLog;

    private static final String RECOMMENDER_NAME = "recommender";

    private static HashMap<String, String> exceptions = new HashMap<String, String>();

    private static CategoriesMappingStorage categoriesMappingStorage;

    private static ShortTermUserProfileCalculator shortTermUserProfileCalculator;

    public static void main(String[] args) {
        final String CONFIGURATION = "configuration";

        Options options = new Options();
        options.addOption(CONFIGURATION, true, "XML Configuration file.");
        CommandLineParser commandLineParser = new PosixParser();
        CommandLine commandLine = null;
        if(args.length != 2) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("Runner", options);
            System.exit(-1);
        }
        try {
            commandLine = commandLineParser.parse(options, args);
        } catch (ParseException e) {
            LOGGER.error("Error while parsing arguments", e);
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("Runner", options);
            System.exit(-1);
        }
        String confFilePath = commandLine.getOptionValue(CONFIGURATION);

        /**
         * Parse the configuration file and instantiates all the needed dependencies
         */
        LOGGER.info("Loading configuration from: '" + confFilePath + "'");
        configurationManager =
                ConfigurationManager.getInstance(confFilePath);

        ProfileStoreConfiguration profileStoreConfiguration 
                = configurationManager.getProfileStoreConfiguration();
        RecommenderConfiguration recommenderConfiguration
                = configurationManager.getRecommenderConfiguration();
        RecommendationsStorageConfiguration recommendationsStorageConfiguration
                = configurationManager.getRecommendationsStorageConfiguration();
        PermanentSearchStorageConfiguration permanentSearchStorageConfiguration
                = configurationManager.getPermanentSearchStorageConfiguration();
        CategoriesMappingStorageConfiguration categoriesMappingStorageStorageConfiguration
                = configurationManager.getCategoriesMappingStorageStorageConfiguration();

        Be3 rdFizer;
        try {
            rdFizer = getRDFizer();
        } catch (TypeHandlerRegistryException e) {
            final String errMsg = "Error while instantiating the rdf-izer";
            LOGGER.error(errMsg, e);
            throw new RuntimeException(errMsg, e);
        }

        profileStore = new SesameVirtuosoProfileStore(profileStoreConfiguration, rdFizer);
        recommender = new SesameVirtuosoRecommender(recommenderConfiguration, rdFizer);
        recommendationsStorage = new KVSRecommendationsStorage(
                recommendationsStorageConfiguration.getProperties()
        );
        permanentSearchStorage = new MyBatisPermanentSearchStorage(permanentSearchStorageConfiguration.getProperties());
        activityLog = new DefaultActivityLogImpl(configurationManager.getActivityLogConfiguration());

        LOGGER.info(
                "ProfileStore, Recommender and RecommendationsStorage, permanentSearchStorage and activityLog correctly instantiated");

        categoriesMappingStorage =
                new MybatisCategoriesMappingStorage(categoriesMappingStorageStorageConfiguration.getProperties());

        //TODO make it configurable
        EnrichmentService dbPediaEnrichmentService = new DBPediaAPI();
        ContentExtractor boilerPipeContentExtractor = new ContentExtractor();

        shortTermUserProfileCalculator = new ShortTermUserProfileCalculator(
                dbPediaEnrichmentService,
                boilerPipeContentExtractor);

        calculateRecommendationsForUsers();
        //TODO (high) reenable
        calculateRecommendationsForProjects();
        calculateRecommendationsForSearches();

        String exceptionsString = stringifyMap(exceptions);
        LOGGER.info("exceptions occurred: \n");
        LOGGER.info(exceptionsString);
    }

    private static void calculateShortTermProfileRecommendations(URI userId, UserProfile profile) {
        //for each user taken from user profiles
        //  get latest N resources mapped
        //TODO get as config parameter
        int amount = 10;
        //or since last period of time?
        List<MappedResource> latestMappedResources = Lists.newArrayList();
        Long userIdLong = 0L;
        UserIdParser uidp = new UserIdParser();
        try {
            userIdLong = uidp.getUserId(userId);
        } catch (UserIdParserException e) {
            LOGGER.error("error while parsing userId from uri: '" + userId.toString() + "'");
        }
        try {
            latestMappedResources.addAll( categoriesMappingStorage.getLatestMappedResources(userIdLong, amount) );
        } catch (CategoriesMappingStorageException e) {
            final String emsg = "couldnt load latest mapped resources for user '" + userId.toString() + "'";
            throw new RuntimeException(emsg, e);
        }
        //  for each one, enrich the com.collective.resources.Enricher class service (or from jar maybe?...)
        //
        ShortTermUserProfile shortTermUserProfile = shortTermUserProfileCalculator.updateProfile(userId, latestMappedResources);

        LOGGER.debug("short term user interests: " + shortTermUserProfile.toString());

        //  do a query to get latest resources that match those URIs
        // this specific method uses the 'interests' field
        Set<WebResourceEnhanced> recommendations = Sets.newHashSet();
        try {
             recommendations.addAll( recommender.getResourceRecommendations(shortTermUserProfile) );
        } catch (RecommenderException e) {
            final String emsg = "error while calculating recommendations for user '" + userId.toString() + "'";
            throw new RuntimeException(emsg, e);
        }

        // delete old recs
        try {
            recommendationsStorage.deleteShortTermResourceRecommendations(userId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "error while deleting old short term recommendations for user '" + userId.toString() + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }

        //  write them back to the KVstore
        try {
            recommendationsStorage.storeShortTermResourceRecommendations(
                    userId,
                    new ArrayList<WebResourceEnhanced>(recommendations)
            );
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while storing short term recs for user: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }

    }

    private static List<Resource> getAllSubjectUris(URI usersGraph) {
        List<Resource> userIdList = new ArrayList<Resource>();
        try {
            userIdList = profileStore.getAllTriplesSubjectsFromGraphIndex(usersGraph);
        } catch (ProfileStoreException e) {
            final String errMsg = "Error while getting all Users from ProfileStorage using graph: '"
                    + usersGraph.toString() + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            //return;
        }
        return userIdList;
    }

    private static String stringifyMap(HashMap<String, String> exceptions) {
        StringBuffer sb = new StringBuffer();
        for (String key : exceptions.keySet()) {
            String value = exceptions.get(key);
            String concat = "K: '" + key + "' V: '" + value + "'\n";
            sb.append(concat);
        }
        return sb.toString();
    }

    private static void calculateRecommendationsForSearches() {
        List<Search> searches = permanentSearchStorage
                .selectAllPermanentSearches();

        /* for each search, calculate the recommendations */

        URI searchGraph;
        searchGraph = configurationManager.getRecommenderConfiguration()
                        .getIndexes()
                        .get("search");

        URI customAnnotationGraphPrefix =
                configurationManager.getRecommenderConfiguration()
                        .getIndexes()
                        .get("custom-annotations");

        for (Search search : searches) {
            URI searchIdURI = null;
            String searchId = Long.toString(search.getId());

            try {
                searchIdURI = new URI(searchGraph + searchId);
            } catch (URISyntaxException e) {
                LOGGER.error("error when building searchURI: " + e.getMessage());
            }


            calculateResourceRecommendationsForSearch(searchIdURI, search,
                    customAnnotationGraphPrefix);
        }
    }

    private static Be3 getRDFizer() throws TypeHandlerRegistryException {
        TypeHandlerRegistry typeHandlerRegistry = new TypeHandlerRegistry();
        URIResourceTypeHandler uriResourceTypeHandler = new URIResourceTypeHandler();
        StringValueTypeHandler stringValueTypeHandler = new StringValueTypeHandler();
        IntegerValueTypeHandler integerValueTypeHandler = new IntegerValueTypeHandler();
        URLResourceTypeHandler urlResourceTypeHandler = new URLResourceTypeHandler();
        DateValueTypeHandler dateValueTypeHandler = new DateValueTypeHandler();
        LongValueTypeHandler longValueTypeHandler = new LongValueTypeHandler();
        typeHandlerRegistry.registerTypeHandler(uriResourceTypeHandler, java.net.URI.class, XMLSchema.ANYURI);
        typeHandlerRegistry.registerTypeHandler(stringValueTypeHandler, String.class, XMLSchema.STRING);
        typeHandlerRegistry.registerTypeHandler(integerValueTypeHandler, Integer.class, XMLSchema.INTEGER);
        typeHandlerRegistry.registerTypeHandler(integerValueTypeHandler, Integer.class, XMLSchema.INT);
        typeHandlerRegistry.registerTypeHandler(urlResourceTypeHandler, URL.class, XMLSchema.ANYURI);
        typeHandlerRegistry.registerTypeHandler(dateValueTypeHandler, Date.class, XMLSchema.DATE);
        typeHandlerRegistry.registerTypeHandler(longValueTypeHandler, Long.class, XMLSchema.LONG);
        return new DefaultTypedBe3Impl(null, typeHandlerRegistry);
    }

    private static void calculateRecommendationsForProjects() {
		/* use the virtuoso profile store graph index to retrieve all users */

        URI projectGraph;
        projectGraph = configurationManager.getRecommenderConfiguration()
                .getIndexes()
                .get("project");
        List<org.openrdf.model.Resource> projectIdList;

        try {
        	projectIdList = profileStore.getAllTriplesSubjectsFromGraphIndex(projectGraph);
        } catch (ProfileStoreException e) {
            final String errMsg = "Error while getting all Projects from ProfileStorage using graph: '" + projectGraph.toString() + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }

        // for each project found
        for (org.openrdf.model.Resource projectIdRes : projectIdList) {
            URI projectId = null;
            try {
                projectId = new URI(projectIdRes.toString());
            } catch (URISyntaxException e) {
                //should never happen
            }

            ProjectProfile profile;

            try {
                profile = profileStore.getProjectProfile(projectId);
            } catch (ProfileStoreException e) {
                final String errMsg = "Error while getting Profile for Project: '" + projectId + "'";
                LOGGER.error(errMsg, e);
                System.exit(-1);
                return;
            }
            calculateResourceRecommandationsForProject(projectId, profile);
            calculateExpertsRecommandations(projectId, profile);
        }		
	}

    private static void calculateExpertsRecommandations(
            URI projectId,
            ProjectProfile projectProfile
    ) {
        LOGGER.info("Calculating Expert users for project: '" + projectId + "'");
        Set<UserProfile> userProfiles;
        try {
            userProfiles = recommender.getExpertUsersForProject(projectProfile);
        } catch (RecommenderException e) {
            final String errMsg = "Error while getting Recommendations for Project: '" + projectId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
        LOGGER.info("Removing old recommendations for project: '" + projectId + "'");
        try {
            recommendationsStorage.deleteExpertsRecommandationsForProject(projectId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while removing Reccomendations for Project: '" + projectId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
        LOGGER.info("Got " + userProfiles.size() + " recommendations");
        LOGGER.info("--- START: PRINTING RECS --- ");
        for (UserProfile userProfile : userProfiles) {
            LOGGER.info(userProfile.getId());
            LOGGER.info(userProfile.getSkills());
        }
        LOGGER.info("--- END: PRINTING RECS --- ");
        try {
            recommendationsStorage.storeExpertsRecommendations(
                    projectId,
                    new ArrayList<UserProfile>(userProfiles)
            );
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while storing Recommendations for Project: '" + projectId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
    }

    private static void calculateResourceRecommandationsForProject(
			URI projectId, ProjectProfile profile) {
		 LOGGER.info("Calculating Resource recommendations for project: '" + projectId + "'");
	        Set<WebResourceEnhanced> resourceRecommendations;
	        try {
	            resourceRecommendations = recommender.getResourceRecommendations(
                        profile
                );
	        } catch (RecommenderException e) {
	            final String errMsg = "Error while getting Recommendations for Project: '" + projectId + "'";
	            LOGGER.error(errMsg, e);
	            System.exit(-1);
	            return;
	        }
	        LOGGER.info("Removing old recommendations for project: '" + projectId + "'");
	        try {
	            recommendationsStorage.deleteResourceRecommendations(projectId);
	        } catch (RecommendationsStorageException e) {
	            final String errMsg = "Error while removing Recomendations for Project: '" + projectId + "'";
	            LOGGER.error(errMsg, e);
	            System.exit(-1);
	            return;
	        }
	        LOGGER.info("Got " + resourceRecommendations.size() + " recommendations");
	        LOGGER.info("--- START: PRINTING RECS --- ");
	        for (WebResourceEnhanced resource : resourceRecommendations) {
	            LOGGER.info(resource.getUrl());
	            LOGGER.info(resource.getTopics());
	        }
	        LOGGER.info("--- END: PRINTING RECS --- ");

            //TODO: (high) move to proper class!!
            //sort recommendations
            Ranker ranker = new WebResourceEnhancedRanker();
            List<WebResourceEnhanced> recommendationsList = new ArrayList<WebResourceEnhanced>(resourceRecommendations);

            try {
                ranker.rank(recommendationsList);
            } catch (RankerException e) {
                final String errMsg = "Error while sorting Recommendations for Project: '" + projectId + "'";
                LOGGER.error(errMsg, e);
                System.exit(-1);
                return;
            }

	        try {
	            recommendationsStorage.storeResourceRecommendations(
                        projectId,
                        recommendationsList
                );
	        } catch (RecommendationsStorageException e) {
	            final String errMsg = "Error while storing Reccomendations for Project: '" + projectId + "'";
	            LOGGER.error(errMsg, e);
	            System.exit(-1);
	            return;
	        }		
	}

	private static void calculateRecommendationsForUsers() {
		/* use the virtuoso profile store graph index to retrieve all users */

        URI usersGraph;
        usersGraph = configurationManager.getRecommenderConfiguration()
                .getIndexes()
                .get("user");

        List<org.openrdf.model.Resource> userIdList;
        try {
            userIdList = profileStore.getAllTriplesSubjectsFromGraphIndex(usersGraph);
        } catch (ProfileStoreException e) {
            final String errMsg = "Error while getting all Users from ProfileStorage using graph: '" + usersGraph.toString() + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }

        // for each user found
        for (org.openrdf.model.Resource userIdRes : userIdList) {

            URI userId = null;
            try {
                userId = new URI(userIdRes.toString());
            } catch (URISyntaxException e) {
                //should never happen
            }

            UserProfile profile;

            try {
                profile = profileStore.getUserProfile(userId);
            } catch (ProfileStoreException e) {
                final String errMsg = "Error while getting Profile for User: '" + userId + "'";
                LOGGER.error(errMsg, e);
                System.exit(-1);
                return;
            }
            calculateResourceRecommendations(userId, profile);
            calculateProjectsRecommendations(userId, profile);
            calculateShortTermProfileRecommendations(userId, profile);
        }
	}

    private static void calculateProjectsRecommendations(URI userId, UserProfile profile) {
        LOGGER.info("Calculating Projects recommendations for user: '" + userId + "'");
        Set<ProjectProfile> projectProfileRecommendations;
        try {
            projectProfileRecommendations = recommender.getProjectRecommendations(
                    profile
            );
        } catch (RecommenderException e) {
            final String errMsg = "Error while getting Recommendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }

        LOGGER.info("Getting old project recommendations for userId: '" + userId + "'");

        Set<ProjectProfile> oldRecommendations = new HashSet<ProjectProfile>();
        List<ProjectProfile> oldRecommendationsList = null;

        try {
            oldRecommendationsList = recommendationsStorage.getProjectRecommendations(userId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while getting old project Recommendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
        }

        if (oldRecommendationsList != null) {
            oldRecommendations.addAll(oldRecommendationsList);
        }

        LOGGER.info("Removing old recommendations for user: '" + userId + "'");
        try {
            recommendationsStorage.deleteProjectProfileRecommendations(userId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while removing Reccomendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
        LOGGER.info("Got " + projectProfileRecommendations.size() + " recommendations");
        LOGGER.info("--- START: PRINTING RECS --- ");
        for (ProjectProfile projectProfile : projectProfileRecommendations) {
            LOGGER.info(projectProfile.getId());
            LOGGER.info(projectProfile.getManifestoConcepts());
        }
        LOGGER.info("--- END: PRINTING RECS --- ");

        /* filtering old recommendations from new ones */
        Set<ProjectProfile> newProjectProfileRecommendations =
                new HashSet<ProjectProfile>(projectProfileRecommendations);
        newProjectProfileRecommendations.removeAll(oldRecommendations);

        int newRecommendations = newProjectProfileRecommendations.size();

        if (newRecommendations > 0) {
            /* build informations to log */
            IntegerField newRecommendationsField =
                    new IntegerField("newProjectRecommendations", newRecommendations);
            StringField userIdField =
                    new StringField("userId", userId.toString());
            /* log the list of projects that have been recommended */
            String commaSeparatedProjects = buildJSON(projectProfileRecommendations);
            StringField projectRecommendationsListField =
                    new StringField("projectRecommendationsListJson", commaSeparatedProjects);

            Field[] fields = new Field[3];
            fields[0] = newRecommendationsField;
            fields[1] = userIdField;
            fields[2] = projectRecommendationsListField;

            /*
            try {
                activityLog.log(RECOMMENDER_NAME, "new project recommendations for user", fields);
            } catch (ActivityLogException e) {
                logger.error("can't log to activityLogger: " + e.getMessage());
            }
            */
        }

        try {
            recommendationsStorage.storeProjectProfileRecommendations(
                    userId, 
                    new ArrayList<ProjectProfile>(projectProfileRecommendations)
            );
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while storing Reccomendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
    }

    private static void calculateResourceRecommendations(URI userId, UserProfile profile) {
        LOGGER.info("Calculating Resource recommendations for user: '" + userId + "'");
        Set<WebResourceEnhanced> resourceRecommendations;
        try {
            resourceRecommendations = recommender.getResourceRecommendations(
                    profile
            );
        } catch (RecommenderException e) {
            final String errMsg = "Error while getting Recommendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
        LOGGER.info("Getting old recommendations for user: '" + userId + "'");

        Set<WebResourceEnhanced> oldRecommendations = new HashSet<WebResourceEnhanced>();
        List<WebResourceEnhanced> oldRecommendationsList = null;

        try {
            oldRecommendationsList =
                    recommendationsStorage.getResourceRecommendations(userId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while getting old Recommendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
        }

        if (oldRecommendationsList != null) {
            oldRecommendations.addAll(oldRecommendationsList);
        }

        LOGGER.info("Removing old recommendations for user: '" + userId + "'");
        try {
            recommendationsStorage.deleteResourceRecommendations(userId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while removing Reccomendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
        LOGGER.info("Got " + resourceRecommendations.size() + " recommendations for user " + userId);
        LOGGER.info("--- START: PRINTING RECS --- ");
        for (WebResourceEnhanced resource : resourceRecommendations) {
            LOGGER.info(resource.getUrl());
            LOGGER.info(resource.getTopics());
        }
        LOGGER.info("--- END: PRINTING RECS --- ");
        //TODO: (high) move ranking to proper class!!
        //sort list
        Ranker ranker = new WebResourceEnhancedRanker();
        List<WebResourceEnhanced> recommendationsList = new ArrayList<WebResourceEnhanced>(resourceRecommendations);

        try {
            ranker.rank(recommendationsList);
        } catch (RankerException e) {
            final String errMsg = "Error while sorting Recommendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }

        /* filtering old recommendations from new ones */
        Set<WebResourceEnhanced> newResourceRecommendations =
                new HashSet<WebResourceEnhanced>(resourceRecommendations);
        newResourceRecommendations.removeAll(oldRecommendations);

        int newRecommendations = newResourceRecommendations.size();

        if (newRecommendations > 0)
        {
            /* build informations to log */
            IntegerField newRecommendationsField =
                    new IntegerField("newRecommendations", newRecommendations);
            StringField userIdField =
                    new StringField("userId", userId.toString());
            /* log the list of links that have been recommended */
            String commaSeparatedResources = buildJSON(recommendationsList);
            StringField recommendationsListField =
                    new StringField("resourcesRecommendationsListJson", commaSeparatedResources);

            Field[] fields = new Field[3];
            fields[0] = newRecommendationsField;
            fields[1] = userIdField;
            fields[2] = recommendationsListField;

            /*
            try {
                activityLog.log(RECOMMENDER_NAME, "new resource recommendations for user", fields);
            } catch (ActivityLogException e) {
                logger.error("can't log to activityLogger: " + e.getMessage());
            }
            */
        }

        try {
            recommendationsStorage.storeResourceRecommendations(
                    userId,
                    recommendationsList
            );
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while storing Reccomendations for User: '" + userId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
    }

    private static void calculateResourceRecommendationsForSearch(URI searchId,
                                                                  Search search,
                                                                  URI customAnnotationsGraphPrefix) {

        Set<WebResourceEnhanced> resourceRecommendations =
                                             new HashSet<WebResourceEnhanced>();

        /* if search contains some common concepts,
         * calculate its recommendations */

        if (search.getCommonUris().size() > 0) {

            LOGGER.info("Calculating Resource recommendations for " +
                        "common concepts of search: '" + searchId + "'");

            try {
                resourceRecommendations = recommender.getResourceRecommendations(
                        search.getCommonUris()
                );
            } catch (RecommenderException e) {
                final String errMsg = "Error while getting Recommendations for Search: '" + searchId + "'";
                LOGGER.error(errMsg, e);
                System.exit(-1);
                return;
            }
        }

        /* if search contains some custom defined concepts,
         * calculate its recommendations */

        if (search.getCustomUris().size() > 0) {
            LOGGER.info("Calculating custom concepts resource " +
                        "recommendations for search: '" + searchId + "'");

        /* get the userId in order for the runner to be able to search only the
         * user's graph with its custom concepts resources' annotations.
         * TODO: med embedding information about the resource in its name is really BAD */
            Long userId = 0L;
            UserIdParser userIdParser = new UserIdParser();
            URI customConceptUrl = search.getCustomUris().get(0).getUrl();

            try {
                userId = userIdParser.getUserId(customConceptUrl);
            } catch (UserIdParserException e) {
                final String errMsg = "Error while parsing " +
                        "userId owner from url: '"
                        + customConceptUrl + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            }

            LOGGER.debug("customConceptUrl: " + customConceptUrl);
            LOGGER.debug("parsed owner of custom concept - userId: " + userId);

            try {
                resourceRecommendations.addAll(
                        recommender.getCustomConceptsResourceRecommendations(
                                search.getCustomUris(),
                                userId,
                                customAnnotationsGraphPrefix));
            } catch (RecommenderException e) {
            //TODO what if the graph with custom annotations doesnt exist?
                final String errMsg = "Error while getting custom concepts " +
                        "Recommendations for Search: '" + searchId + "' in " +
                        "annotations graph '"
                        + customAnnotationsGraphPrefix.toString() + "'";
                LOGGER.warn(errMsg, e);
                //store exceptions messages
                final String graphId = customAnnotationsGraphPrefix.toString() + userId;
                exceptions.put(graphId, e.toString());
            }

            LOGGER.info("Calculated '" + resourceRecommendations.size() +
                        "' recommendations for search '" + searchId + "' " +
                        "from annotations graph '" + customAnnotationsGraphPrefix.toString() +
                        userId + "'");
        }

        LOGGER.info("Getting old recommendations for search: '" + searchId + "'");

        Set<WebResourceEnhanced> oldRecommendations = new HashSet<WebResourceEnhanced>();
        List<WebResourceEnhanced> oldRecommendationsList = null;

        try {
            oldRecommendationsList = recommendationsStorage.getResourceRecommendations(searchId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while getting old Recommendations for Search: '" + searchId + "'";
            LOGGER.error(errMsg, e);
        }

        if (oldRecommendationsList != null) {
            oldRecommendations.addAll(oldRecommendationsList);
        }

        LOGGER.info("Removing old recommendations for search: '" + searchId + "'");
        try {
            recommendationsStorage.deleteResourceRecommendations(searchId);
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while removing " +
                    "Recommendations for search: '" + searchId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
        LOGGER.info("Got " + resourceRecommendations.size() + " recommendations");
        LOGGER.info("--- START: PRINTING RECS --- ");
        for (WebResourceEnhanced resource : resourceRecommendations) {
            LOGGER.info(resource.getUrl());
            LOGGER.info(resource.getTopics());
        }
        LOGGER.info("--- END: PRINTING RECS --- ");
        //TODO: (high) move ranking to proper class!!
        //sort list
        Ranker ranker = new WebResourceEnhancedRanker();
        List<WebResourceEnhanced> recommendationsList =
                new ArrayList<WebResourceEnhanced>(resourceRecommendations);

        try {
            ranker.rank(recommendationsList);
        } catch (RankerException e) {
            final String errMsg = "Error while sorting Recommendations for Search: '" + searchId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }

        /* filtering old recommendations from new ones */
        Set<WebResourceEnhanced> newResourceRecommendations =
                new HashSet<WebResourceEnhanced>(resourceRecommendations);
        newResourceRecommendations.removeAll(oldRecommendations);

        int newRecommendations = newResourceRecommendations.size();

        //log activity
        if (newRecommendations > 0) {
            logRecommendationActivity(searchId, recommendationsList, newRecommendations);
        }

        try {
            recommendationsStorage.storeResourceRecommendations(
                    searchId,
                    recommendationsList
            );
        } catch (RecommendationsStorageException e) {
            final String errMsg = "Error while storing " +
                    "Recomendations for Search: '" + searchId + "'";
            LOGGER.error(errMsg, e);
            System.exit(-1);
            return;
        }
        LOGGER.info("stored " + recommendationsList.size() + " recommendations to '" +
                    searchId.toString() + "' search Id");
    }

    private static void logRecommendationActivity(
                            URI searchId,
                            List<WebResourceEnhanced> recommendationsList,
                            int newRecommendations) {
        /* build informations to log */
        IntegerField newRecommendationsField =
                new IntegerField("newRecommendations", newRecommendations);
        StringField searchIdField =
                new StringField("searchId", searchId.toString());
        /* log the list of links that have been recommended */
        String commaSeparatedResources = buildJSON(recommendationsList);
        StringField recommendationsListField =
                new StringField("searchRecommendationsListJson", commaSeparatedResources);

        Field[] recAmountFields = new Field[3];
        recAmountFields[0] = newRecommendationsField;
        recAmountFields[1] = searchIdField;
        recAmountFields[2] = recommendationsListField;

        /*
        try {
            activityLog.log(RECOMMENDER_NAME, "new resource recommendations for search",
                    recAmountFields);
        } catch (ActivityLogException e) {
            logger.error("can't log to activityLogger: " + e.getMessage());
        }
        */
    }

    /* encodes a list of links in JSON */
    private static String buildJSON(List<WebResourceEnhanced> recommendationsList) {

        StringBuilder builder = new StringBuilder();
        builder.append("[");

        String prefix = "";

        for (WebResourceEnhanced webResource : recommendationsList) {
            builder.append(prefix);
            prefix = ",";
            builder.append("\"" + webResource.getUrl().toString() + "\"");
        }
        builder.append("]");

        String webResourcesList = builder.toString();

        return webResourcesList;
    }

    /* encodes a list of project ids in JSON */
    private static String buildJSON(Set<ProjectProfile> projectProfileRecommendations) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");

        String prefix = "";

        for (ProjectProfile projectProfile : projectProfileRecommendations) {
            builder.append(prefix);
            prefix = ",";
            builder.append("\"" + projectProfile.getId().toString() + "\"");
        }

        builder.append("]");

        String projectProfileList = builder.toString();

        return projectProfileList;
    }
}
