/*
 * This file is a part of the SchemaSpy project (http://schemaspy.org).
 * Copyright (C) 2004, 2005, 2006, 2007, 2008, 2009, 2010, 2011 John Currier
 *
 * SchemaSpy is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * SchemaSpy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.schemaspy;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.schemaspy.cli.CommandLineArguments;
import org.schemaspy.model.*;
import org.schemaspy.model.xml.SchemaMeta;
import org.schemaspy.output.OutputException;
import org.schemaspy.output.OutputProducer;
import org.schemaspy.output.xml.dom.DOMUtil;
import org.schemaspy.output.xml.dom.XmlProducerUsingDOM;
import org.schemaspy.output.xml.dom.XmlTableFormatter;
import org.schemaspy.service.DatabaseService;
import org.schemaspy.service.SqlService;
import org.schemaspy.util.*;
import org.schemaspy.view.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.*;

//import javax.xml.validation.Schema;

/**
 * @author John Currier
 */
@Component
public class SchemaAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SqlService sqlService;

    private final DatabaseService databaseService;

    private final CommandLineArguments commandLineArguments;

    private List<OutputProducer> outputProducers = new ArrayList<>();

    public SchemaAnalyzer(SqlService sqlService, DatabaseService databaseService, CommandLineArguments commandLineArguments) {
        this.sqlService = Objects.requireNonNull(sqlService);
        this.databaseService = Objects.requireNonNull(databaseService);
        this.commandLineArguments = Objects.requireNonNull(commandLineArguments);
        addOutputProducer(new XmlProducerUsingDOM());
    }

    public SchemaAnalyzer addOutputProducer(OutputProducer outputProducer) {
        outputProducers.add(outputProducer);
        return this;
    }

    public Database analyze(Config config) throws SQLException, IOException {
    	// don't render console-based detail unless we're generating HTML (those probably don't have a user watching)
    	// and not already logging fine details (to keep from obfuscating those)

        boolean render = config.isHtmlGenerationEnabled();
        ProgressListener progressListener = new ConsoleProgressListener(render, commandLineArguments);
        
    	// if -all(evaluteAll) or -schemas given then analyzeMultipleSchemas  
        List<String> schemas = config.getSchemas();
        if (schemas != null || config.isEvaluateAllEnabled()) {
        	return this.analyzeMultipleSchemas(config, progressListener);
        } else {
            File outputDirectory = commandLineArguments.getOutputDirectory();
            Objects.requireNonNull(outputDirectory);
            String schema = commandLineArguments.getSchema();
            return analyze(schema, config, outputDirectory, progressListener);
        }
    }

	public Database analyzeMultipleSchemas(Config config, ProgressListener progressListener)throws SQLException, IOException {
        try {
            // following params will be replaced by something appropriate
            List<String> args = config.asList();
            args.remove("-schemas");
            args.remove("-schemata");
           
            List<String> schemas = config.getSchemas();
            Database db = null;
        	String schemaSpec = config.getSchemaSpec();
            Connection connection = this.getConnection(config);
            DatabaseMetaData meta = connection.getMetaData();
            //-all(evaluteAll) given then get list of the database schemas
            if (schemas == null || config.isEvaluateAllEnabled()) {
            	if(schemaSpec==null)
            		schemaSpec=".*";
                System.out.println("Analyzing schemas that match regular expression '" + schemaSpec + "':");
                System.out.println("(use -schemaSpec on command line or in .properties to exclude other schemas)");
                schemas = DbAnalyzer.getPopulatedSchemas(meta, schemaSpec, false);
                if (schemas.isEmpty())
                	schemas = DbAnalyzer.getPopulatedSchemas(meta, schemaSpec, true);
                if (schemas.isEmpty())
                	schemas = Arrays.asList(config.getUser());
            }

        	System.out.println("Analyzing schemas: "+schemas.toString());
        	
	        String dbName = config.getDb();
	        File outputDir = commandLineArguments.getOutputDirectory();
	        // set flag which later on used for generation rootPathtoHome link.
	        config.setOneOfMultipleSchemas(true);

	        List<MustacheSchema> mustacheSchemas =new ArrayList<MustacheSchema>();
	        MustacheCatalog  mustacheCatalog = null; 
	        for (String schema : schemas) {
	        	// reset -all(evaluteAll) and -schemas parametter to avoid infinite loop! now we are analyzing single schema
	        	config.setSchemas(null);
	            config.setEvaluateAllEnabled(false);
	            if (dbName == null)
	            	config.setDb(schema);
	            else
	        		config.setSchema(schema);

                System.out.println("Analyzing " + schema);
                System.out.flush();
                File outputDirForSchema = new File(outputDir, schema);
                db = this.analyze(schema, config, outputDirForSchema, progressListener);
                if (db == null) //if any of analysed schema returns null
                    return db;
                mustacheSchemas.add(new MustacheSchema(db.getSchema(),""));
                mustacheCatalog = new MustacheCatalog(db.getCatalog(), "");
	        }

            prepareLayoutFiles(outputDir);
            HtmlMultipleSchemasIndexPage.getInstance().write(outputDir, dbName, mustacheCatalog ,mustacheSchemas, meta);
	        
	        return db;
        } catch (Config.MissingRequiredParameterException missingParam) {
            config.dumpUsage(missingParam.getMessage(), missingParam.isDbTypeSpecific());
            return null;
        }
    }

    public Database analyze(String schema, Config config, File outputDir,  ProgressListener progressListener) throws SQLException, IOException {
        try {
            LOGGER.info("Starting schema analysis");

            FileUtils.forceMkdir(outputDir);

            String dbName = config.getDb();

            String catalog = commandLineArguments.getCatalog();

            DatabaseMetaData meta = sqlService.connect(config);

            LOGGER.debug("supportsSchemasInTableDefinitions: {}", meta.supportsSchemasInTableDefinitions());
            LOGGER.debug("supportsCatalogsInTableDefinitions: {}", meta.supportsCatalogsInTableDefinitions());

            // set default Catalog and Schema of the connection
            if(schema == null)
            	schema = meta.getConnection().getSchema();
            if(catalog == null)
            	catalog = meta.getConnection().getCatalog();

            SchemaMeta schemaMeta = config.getMeta() == null ? null : new SchemaMeta(config.getMeta(), dbName, schema);
            if (config.isHtmlGenerationEnabled()) {
                FileUtils.forceMkdir(new File(outputDir, "tables"));
                FileUtils.forceMkdir(new File(outputDir, "diagrams/summary"));

                LOGGER.info("Connected to {} - {}", meta.getDatabaseProductName(), meta.getDatabaseProductVersion());

                if (schemaMeta != null && schemaMeta.getFile() != null) {
                    LOGGER.info("Using additional metadata from {}", schemaMeta.getFile());
                }
            }

            //
            // create our representation of the database
            //
            Database db = new Database(config, meta, dbName, catalog, schema, schemaMeta, progressListener);
            databaseService.gatheringSchemaDetails(config, db, progressListener);

            long duration = progressListener.startedGraphingSummaries();

            Collection<Table> tables = new ArrayList<Table>(db.getTables());
            tables.addAll(db.getViews());

            if (tables.isEmpty()) {
                dumpNoTablesMessage(schema, config.getUser(), meta, config.getTableInclusions() != null);
                if (!config.isOneOfMultipleSchemas()) // don't bail if we're doing the whole enchilada
                    throw new EmptySchemaException();
            }

            if (config.isHtmlGenerationEnabled()) {
                generateHtmlDoc(config, progressListener, outputDir, db, duration, tables);
            }

            outputProducers.forEach(
                    outputProducer -> {
                        try {
                            outputProducer.generate(db, outputDir);
                        } catch (OutputException oe) {
                           if (config.isOneOfMultipleSchemas()) {
                               LOGGER.warn("Failed to produce output", oe);
                           } else {
                               throw oe;
                           }
                        }
                    });

            List<ForeignKeyConstraint> recursiveConstraints = new ArrayList<ForeignKeyConstraint>();

            // create an orderer to be able to determine insertion and deletion ordering of tables
            TableOrderer orderer = new TableOrderer();

            // side effect is that the RI relationships get trashed
            // also populates the recursiveConstraints collection
            List<Table> orderedTables = orderer.getTablesOrderedByRI(db.getTables(), recursiveConstraints);

            writeOrders(outputDir, orderedTables);

            duration = progressListener.finishedGatheringDetails();
            long overallDuration = progressListener.finished(tables, config);

            if (config.isHtmlGenerationEnabled()) {
                LOGGER.info("Wrote table details in {} seconds", duration / 1000);

                LOGGER.info("Wrote relationship details of {} tables/views to directory '{}' in {} seconds.", tables.size(), outputDir, overallDuration / 1000);
                LOGGER.info("View the results by opening {}", new File(outputDir, "index.html"));
            }

            return db;
        } catch (Config.MissingRequiredParameterException missingParam) {
            config.dumpUsage(missingParam.getMessage(), missingParam.isDbTypeSpecific());
            return null;
        }
    }

    private void writeOrders(File outputDir, List<Table> orderedTables) throws IOException {
        LineWriter out;
        out = new LineWriter(new File(outputDir, "insertionOrder.txt"), 16 * 1024, Config.DOT_CHARSET);
        try {
            TextFormatter.getInstance().write(orderedTables, false, out);
        } catch (IOException e) {
            throw new IOException(e);
        } finally {
            out.close();
        }

        out = new LineWriter(new File(outputDir, "deletionOrder.txt"), 16 * 1024, Config.DOT_CHARSET);
        try {
            Collections.reverse(orderedTables);
            TextFormatter.getInstance().write(orderedTables, false, out);
        } catch (IOException e) {
            throw new IOException(e);
        } finally {
            out.close();
        }
    }

    private void generateHtmlDoc(Config config, ProgressListener progressListener, File outputDir, Database db, long duration, Collection<Table> tables) throws IOException {
        LineWriter out;
        LOGGER.info("Gathered schema details in {} seconds", duration / 1000);
        LOGGER.info("Writing/graphing summary");

        prepareLayoutFiles(outputDir);

        progressListener.graphingSummaryProgressed();

        boolean showDetailedTables = tables.size() <= config.getMaxDetailedTables();
        final boolean includeImpliedConstraints = config.isImpliedConstraintsEnabled();

        // if evaluating a 'ruby on rails-based' database then connect the columns
        // based on RoR conventions
        // note that this is done before 'hasRealRelationships' gets evaluated so
        // we get a relationships ER diagram
        if (config.isRailsEnabled())
            DbAnalyzer.getRailsConstraints(db.getTablesByName());

        File summaryDir = new File(outputDir, "diagrams/summary");

        // generate the compact form of the relationships .dot file
        String dotBaseFilespec = "relationships";
        out = new LineWriter(new File(summaryDir, dotBaseFilespec + ".real.compact.dot"), Config.DOT_CHARSET);
        WriteStats stats = new WriteStats(tables);
        DotFormatter.getInstance().writeRealRelationships(db, tables, true, showDetailedTables, stats, out, outputDir);
        boolean hasRealRelationships = stats.getNumTablesWritten() > 0 || stats.getNumViewsWritten() > 0;
        out.close();

        if (hasRealRelationships) {
            // real relationships exist so generate the 'big' form of the relationships .dot file
            progressListener.graphingSummaryProgressed();
            out = new LineWriter(new File(summaryDir, dotBaseFilespec + ".real.large.dot"), Config.DOT_CHARSET);
            DotFormatter.getInstance().writeRealRelationships(db, tables, false, showDetailedTables, stats, out, outputDir);
            out.close();
        }

        // getting implied constraints has a side-effect of associating the parent/child tables, so don't do it
        // here unless they want that behavior
        List<ImpliedForeignKeyConstraint> impliedConstraints;
        if (includeImpliedConstraints)
            impliedConstraints = DbAnalyzer.getImpliedConstraints(tables);
        else
            impliedConstraints = new ArrayList<>();

        List<Table> orphans = DbAnalyzer.getOrphans(tables);
        config.setHasOrphans(!orphans.isEmpty() && Dot.getInstance().isValid());
        config.setHasRoutines(!db.getRoutines().isEmpty());

        progressListener.graphingSummaryProgressed();

        File impliedDotFile = new File(summaryDir, dotBaseFilespec + ".implied.compact.dot");
        out = new LineWriter(impliedDotFile, Config.DOT_CHARSET);
        boolean hasImplied = DotFormatter.getInstance().writeAllRelationships(db, tables, true, showDetailedTables, stats, out, outputDir);

        Set<TableColumn> excludedColumns = stats.getExcludedColumns();
        out.close();
        if (hasImplied) {
            impliedDotFile = new File(summaryDir, dotBaseFilespec + ".implied.large.dot");
            out = new LineWriter(impliedDotFile, Config.DOT_CHARSET);
            DotFormatter.getInstance().writeAllRelationships(db, tables, false, showDetailedTables, stats, out, outputDir);
            out.close();
        } else {
            Files.deleteIfExists(impliedDotFile.toPath());
        }

        HtmlRelationshipsPage.getInstance().write(db, summaryDir, dotBaseFilespec, hasRealRelationships, hasImplied, excludedColumns,
                progressListener, outputDir);

        progressListener.graphingSummaryProgressed();

        File orphansDir = new File(outputDir, "diagrams/orphans");
        FileUtils.forceMkdir(orphansDir);
        HtmlOrphansPage.getInstance().write(db, orphans, orphansDir, outputDir);
        out.close();

        progressListener.graphingSummaryProgressed();

        HtmlMainIndexPage.getInstance().write(db, tables, db.getRemoteTables(), outputDir);

        progressListener.graphingSummaryProgressed();

        List<ForeignKeyConstraint> constraints = DbAnalyzer.getForeignKeyConstraints(tables);
        HtmlConstraintsPage constraintIndexFormatter = HtmlConstraintsPage.getInstance();
        constraintIndexFormatter.write(db, constraints, tables, outputDir);

        progressListener.graphingSummaryProgressed();

        HtmlAnomaliesPage.getInstance().write(db, tables, impliedConstraints, outputDir);

        progressListener.graphingSummaryProgressed();

        for (HtmlColumnsPage.ColumnInfo columnInfo : HtmlColumnsPage.getInstance().getColumnInfos().values()) {
            HtmlColumnsPage.getInstance().write(db, tables, columnInfo, outputDir);
        }

        progressListener.graphingSummaryProgressed();

        HtmlRoutinesPage.getInstance().write(db, outputDir);

        // create detailed diagrams

        duration = progressListener.startedGraphingDetails();

        LOGGER.info("Completed summary in {} seconds", duration / 1000);
        LOGGER.info("Writing/diagramming details");

        generateTables(progressListener, outputDir, db, tables, stats);
        HtmlComponentPage.getInstance().write(db, tables, outputDir);
    }

    /**
     * This method is responsible to copy layout folder to destination directory and not copy template .html files
     * @param outputDir
     * @throws IOException
     */
    private void prepareLayoutFiles(File outputDir) throws IOException {
        URL url = getClass().getResource("/layout");

        IOFileFilter notHtmlFilter = FileFilterUtils.notFileFilter(FileFilterUtils.suffixFileFilter(".html"));
        FileFilter filter = FileFilterUtils.and(notHtmlFilter);
        //cleanDirectory(outputDir,"/diagrams");
        //cleanDirectory(outputDir,"/tables");
        ResourceWriter.copyResources(url, outputDir, filter);
    }

    private Connection getConnection(Config config) throws InvalidConfigurationException, IOException {

        Properties properties = config.getDbProperties();

        ConnectionURLBuilder urlBuilder = new ConnectionURLBuilder(config, properties);
        if (config.getDb() == null)
            config.setDb(urlBuilder.build());

        String driverClass = properties.getProperty("driver");
        String driverPath = properties.getProperty("driverPath");
        if (driverPath == null)
            driverPath = "";
        if (config.getDriverPath() != null)
            driverPath = config.getDriverPath() + File.pathSeparator + driverPath;

        DbDriverLoader driverLoader = new DbDriverLoader();
        return driverLoader.getConnection(config, urlBuilder.build(), driverClass, driverPath);
    }

    private void generateTables(ProgressListener progressListener, File outputDir, Database db, Collection<Table> tables, WriteStats stats) throws IOException {
        HtmlTablePage tableFormatter = HtmlTablePage.getInstance();
        for (Table table : tables) {
            progressListener.graphingDetailsProgressed(table);
            LOGGER.debug("Writing details of {}", table.getName());

            tableFormatter.write(db, table, outputDir, stats);
        }
    }

    /**
     * dumpNoDataMessage
     *
     * @param schema String
     * @param user String
     * @param meta DatabaseMetaData
     */
    private static void dumpNoTablesMessage(String schema, String user, DatabaseMetaData meta, boolean specifiedInclusions) throws SQLException {
        System.out.println();
        System.out.println();
        System.out.println("No tables or views were found in schema '" + schema + "'.");
        List<String> schemas = null;
        Exception failure = null;
        try {
            schemas = DbAnalyzer.getSchemas(meta);
        } catch (SQLException | RuntimeException exc) {
            failure = exc;
        }

        if (schemas == null) {
            System.out.println("The user you specified (" + user + ')');
            System.out.println("  might not have rights to read the database metadata.");
            System.out.flush();
            if (failure != null)    // to appease the compiler
                failure.printStackTrace();
            return;
        } else if (schema == null || schemas.contains(schema)) {
            System.out.println("The schema exists in the database, but the user you specified (" + user + ')');
            System.out.println("  might not have rights to read its contents.");
            if (specifiedInclusions) {
                System.out.println("Another possibility is that the regular expression that you specified");
                System.out.println("  for what to include (via -i) didn't match any tables.");
            }
        } else {
            System.out.println("The schema does not exist in the database.");
            System.out.println("Make sure that you specify a valid schema with the -s option and that");
            System.out.println("  the user specified (" + user + ") can read from the schema.");
            System.out.println("Note that schema names are usually case sensitive.");
        }
        System.out.println();
        boolean plural = schemas.size() != 1;
        System.out.println(schemas.size() + " schema" + (plural ? "s" : "") + " exist" + (plural ? "" : "s") + " in this database.");
        System.out.println("Some of these \"schemas\" may be users or system schemas.");
        System.out.println();
        for (String unknown : schemas) {
            System.out.print(unknown + " ");
        }

        System.out.println();
        List<String> populatedSchemas = DbAnalyzer.getPopulatedSchemas(meta);
        if (populatedSchemas.isEmpty()) {
            System.out.println("Unable to determine if any of the schemas contain tables/views");
        } else {
            System.out.println("These schemas contain tables/views that user '" + user + "' can see:");
            System.out.println();
            for (String populated : populatedSchemas) {
                System.out.print(" " + populated);
            }
        }
    }
}
