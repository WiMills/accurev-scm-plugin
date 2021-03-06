package hudson.plugins.accurev;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.plugins.accurev.browsers.AccurevWeb;
import hudson.plugins.accurev.extensions.AccurevSCMExtension;
import hudson.plugins.accurev.extensions.AccurevSCMExtensionDescriptor;
import hudson.plugins.accurev.util.*;
import hudson.plugins.accurev.util.Build;
import hudson.scm.*;
import hudson.security.ACL;
import hudson.util.DescribableList;
import jenkins.plugins.accurev.AccurevSCMHead;
import jenkins.plugins.accurevclient.Accurev;
import jenkins.plugins.accurevclient.AccurevClient;
import jenkins.plugins.accurevclient.AccurevException;
import jenkins.plugins.accurevclient.commands.PopulateCommand;
import jenkins.plugins.accurevclient.model.AccurevStream;
import jenkins.plugins.accurevclient.model.AccurevTransaction;
import jenkins.plugins.accurevclient.model.AccurevTransactionVersion;
import jenkins.scm.api.SCMRevisionAction;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Serializable;
import java.io.Writer;
import java.net.MalformedURLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Iterables.isEmpty;
import static com.google.common.collect.Lists.newArrayList;
import static hudson.scm.PollingResult.BUILD_NOW;
import static hudson.scm.PollingResult.NO_CHANGES;

public class AccurevSCM extends SCM implements Serializable {

    private static final long serialVersionUID = 1L;

    private String source = null;
    private List<StreamSpec> streams;
    private List<ServerRemoteConfig> serverRemoteConfigs;

    @CheckForNull
    private AccurevRepositoryBrowser repositoryBrowser;
    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="Known non-serializable field")
    private AccurevClient ac;
    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="Known non-serializable field")
    private DescribableList<AccurevSCMExtension, AccurevSCMExtensionDescriptor> extensions = null;


    private static final String ACCUREV_STREAM = "ACCUREV_STREAM";
    private static final String ACCUREV_TRANSACTION = "ACCUREV_TRANSACTION";
    private static final String ACCUREV_SERVER = "ACCUREV_SERVER";
    private static final String ACCUREV_PORT = "ACCUREV_PORT";

    public AccurevSCM(String source) {
        this.source = source;
        streams = Collections.singletonList(new StreamSpec(""));
        this.extensions = new DescribableList<>(Saveable.NOOP, Util.fixNull(extensions));
    }



    @Override
    public boolean supportsPolling() {
        return true;
    }

    @Override
    public boolean requiresWorkspaceForPolling() {
        return requiresWorkspaceForPolling(new EnvVars());
    }

    /* Package protected for test access */
    boolean requiresWorkspaceForPolling(EnvVars environment) {
        for (AccurevSCMExtension ext : getExtensions()) {
            if (ext.requiresWorkspaceForPolling()) return true;
        }
        return getSingleStream() == null;
    }

    @Exported
    public List<StreamSpec> getBranches() {
        return streams;
    }


    public DescribableList<AccurevSCMExtension, AccurevSCMExtensionDescriptor> getExtensions() {
        return extensions;
    }

    @DataBoundConstructor
    public AccurevSCM(List<ServerRemoteConfig> serverRemoteConfigs, List<StreamSpec> streams,  List<AccurevSCMExtension> extensions, @CheckForNull AccurevRepositoryBrowser browser){
        this.streams = isEmpty(streams) ? newArrayList(new StreamSpec("wasEmpty", "")) : streams;
        this.serverRemoteConfigs = serverRemoteConfigs;
        this.extensions = new DescribableList<>(Saveable.NOOP, Util.fixNull(extensions));
        this.repositoryBrowser = browser;
    }



    @Override
    public ChangeLogParser createChangeLogParser() {
        return new AccurevChangeLogParser();
    }

    public static List<ServerRemoteConfig> createDepotList(String host, String port, String credentialsId){
        List<ServerRemoteConfig> depotList = new ArrayList<>();
        depotList.add(new ServerRemoteConfig(host, port, credentialsId));
        return depotList;
    }

    @Whitelisted
    @Exported
    @Override
    public AccurevRepositoryBrowser getBrowser() {
        return repositoryBrowser;
    }

    public void setBrowser(AccurevRepositoryBrowser browser) {
        this.repositoryBrowser = browser;
    }

    @CheckForNull
    @Override
    public RepositoryBrowser<?> guessBrowser() {
        try {
            return new AccurevWeb("http://" + getServerRemoteConfigs().get(0).getHost() + ":8080/accurev/");
        } catch (MalformedURLException x) {
            LOGGER.log(Level.FINE, null, x); // OK, could just be a local directory path
            return null;
        }
    }


    @Override
    public PollingResult compareRemoteRevisionWith(Job<?, ?> project, Launcher launcher, FilePath workspace, final @NonNull TaskListener listener, @NotNull SCMRevisionState baseline) throws IOException, InterruptedException {
        // Poll for changes. Are there any unbuilt revisions that Hudson ought to build ?
        listener.getLogger().println("Using strategy: " + getBuildChooser().getDisplayName());
        final Run lastBuild = project.getLastBuild();
        if (lastBuild == null) {
            // If we've never been built before, well, gotta build!
            listener.getLogger().println("[poll] No previous build, so forcing an initial build.");
            return BUILD_NOW;
        }

        final Node node = AccurevUtils.workspaceToNode(workspace);
        EnvVars environment = project.getEnvironment(node, listener);
        Accurev accurev = Accurev.with(listener, environment, launcher);
        AccurevClient client = accurev.getClient();
        final BuildData buildData = getBuildData(lastBuild);

        Collection<AccurevTransaction> candidateTransactions = getBuildChooser().getCandidateTransactions(true, getSingleStream(), ac, listener, buildData);

        for(AccurevTransaction transaction : candidateTransactions) {
            if (!isTransactionExcluded(client, transaction, listener, buildData)) {
                return BUILD_NOW;
            }
        }
        return NO_CHANGES;
    }

    private boolean isTransactionExcluded(AccurevClient client, AccurevTransaction transaction, TaskListener listener, BuildData buildData) throws IOException, InterruptedException  {
        try {
            Boolean excludeThisTransaction = null;
            for (AccurevSCMExtension ext : extensions) {
                excludeThisTransaction = ext.isTransactionExcluded( this, client, transaction, listener, buildData);

                if (excludeThisTransaction!=null)
                    break;
            }
            return excludeThisTransaction != null && excludeThisTransaction;
        } catch (AccurevException e) {
            e.printStackTrace(listener.error("Failed to determine if we want to exclude transaction " + transaction.getId()));
            return false;
        }
    }

    public AccurevClient getClient() {
        return ac;
    }

    private void createClient(TaskListener listener, EnvVars environment, Run<?,?> build, FilePath workspace, Launcher launcher) throws IOException, InterruptedException {

        if (workspace != null) {
            workspace.mkdirs();
        }

        Accurev accurev = Accurev.with(listener, environment, launcher).at(workspace).on(getServerRemoteConfigs().get(0).getUrl());
        this.ac = accurev.getClient();

        for (ServerRemoteConfig src : getServerRemoteConfigs()) {
            String srcCredentialsId = src.getCredentialsId();
            if(srcCredentialsId != null){
                List<StandardUsernamePasswordCredentials> serverCredentials = CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        build.getParent(),
                        build.getParent() instanceof Queue.Task
                                ? Tasks.getAuthenticationOf((Queue.Task)build.getParent())
                                : ACL.SYSTEM,
                        URIRequirementBuilder.fromUri("").build()
                );
                CredentialsMatcher srcMatcher = CredentialsMatchers.withId(srcCredentialsId);
                CredentialsMatcher idMatcher = CredentialsMatchers.allOf(srcMatcher, AccurevClient.Companion.getCREDENTIALS_MATCHER());
                StandardUsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(serverCredentials, idMatcher);

                if(credentials != null) {

                    ac.setCredentials(credentials);
                    ac.login().username(credentials.getUsername()).password(credentials.getPassword()).execute();

                    if(build.getParent() != null && build.getParent().getLastBuild() != null){
                        CredentialsProvider.track((build.getParent()).getLastBuild(),credentials);
                    }
                }
            }
        }
    }

    private String createUrl(ServerRemoteConfig serverRemoteConfig) {
        return serverRemoteConfig.getHost() + ":" + serverRemoteConfig.getPort();
    }

    @Exported
    public List<StreamSpec> getStreams() {return streams;}

    @Exported
    public List<ServerRemoteConfig> getServerRemoteConfigs() {
        if (serverRemoteConfigs == null) {
            /* Prevent NPE when no remote config defined */
            serverRemoteConfigs = new ArrayList<>();
        }
        return Collections.unmodifiableList(serverRemoteConfigs);}



    @Override
    public void checkout(Run<?, ?> build, Launcher launcher, FilePath workspace, TaskListener listener, File changelogFile, SCMRevisionState baseline)
            throws IOException, InterruptedException {

        BuildData prevBuildData = getBuildData(build.getPreviousBuild());
        BuildData buildData = copyBuildData(build.getPreviousBuild());
        System.out.println("Checkout started");
        listener.getLogger().println(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime()) + "Checkout started");
        if (VERBOSE && buildData.lastBuild != null) {
            listener.getLogger().println("Last Built TransactionId: " + buildData.lastBuild.transaction);
        }
        EnvVars environment = build.getEnvironment(listener);
        createClient(listener, environment, build, workspace, launcher);

        retrieveChanges(build, ac, listener);
        Build transactionToBuild = determineTransactionToBuild(build, buildData, environment, ac, listener);
        List<BuildData> actions = build.getActions(BuildData.class);

        if(!actions.isEmpty()){
            buildData.setIndex(actions.size()+1);
        }
        build.addAction(buildData);

        Set files = new HashSet();
        files.add(".");
        ac.update();
        PopulateCommand populateCommand = ac.populate();

        boolean requiresWorkspace = false;

        for (AccurevSCMExtension ext : this.getExtensions()) {
            requiresWorkspace = ext.requiresWorkspace();
            if(requiresWorkspace) break;
        }

        if(!requiresWorkspace) {
            populateCommand.stream(transactionToBuild.marked.getName());
        }

        populateCommand.timespec(Long.toString((transactionToBuild.transaction.getId()))).overwrite(true).elements(files);

        for (AccurevSCMExtension ext : this.getExtensions()) {
            ext.decoratePopulateCommand(this, build, ac, listener, populateCommand);
        }

        populateCommand.execute();
        listener.getLogger().println(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime()) + "Checkout done");
        if (changelogFile != null) {
            computeChangeLog(ac, listener, transactionToBuild, prevBuildData, buildData, new FilePath(changelogFile));
        }
    }

    @Override
    public void buildEnvVars(AbstractBuild<?, ?> build, Map<String, String> env) {
        buildEnvironment(build, env);
    }


    @Override
    public void buildEnvironment(@Nonnull Run<?, ?> build, @NonNull Map<String, String> env) {
        try {
            AccurevTransaction transaction = getBuildData(build).getLastBuiltTransaction();
            env.put(ACCUREV_STREAM, getStreams().get(0).getName());
            env.put(ACCUREV_TRANSACTION, Long.toString(transaction.getId()));
            for (ServerRemoteConfig config : serverRemoteConfigs) {
                env.put(ACCUREV_SERVER, config.getHost());
                env.put(ACCUREV_PORT, config.getPort());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "failed to load environment" + e.getMessage());
        }
    }


    private void computeChangeLog(AccurevClient ac, TaskListener listener, Build transactionToBuild, BuildData prevBuildData, BuildData buildData, FilePath changelogFile) {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ");
        try (Writer out = new OutputStreamWriter(changelogFile.write(), "UTF-8")){
            for(AccurevTransaction at : transactionToBuild.updatesSincePrevBuild){
                out.write("transaction: " + at.getId()  + "\n");
                out.write("stream: " + at.getStream() + "\n");
                out.write("    " + at.getComment() + "    \n");
                out.write("Type: " + at.getType() + "\n");
                out.write("User: " + at.getUser() + "\n");
                if( at.getVersion() != null){
                    for(AccurevTransactionVersion version : at.getVersion()){
                        out.write("File: " + version.getPath() + "\n");
                    }
                }
                out.write("Time: " + df.format(at.getTime()) + "\n");
            }
        }catch(InterruptedException e){
            e.printStackTrace(listener.error("Unable to retrieve changeset"));
        }catch(IOException e) {
            e.printStackTrace(listener.error("Could not write to file"));
        }
    }


    private Build determineTransactionToBuild(Run build, BuildData buildData, EnvVars environment, AccurevClient ac, TaskListener listener) {
        /**
        * Determines the TransactionID that needs to be built
        * The stream contains files with a newer transaction ID, iterate over the IDs
        *
        */
        Collection<AccurevTransaction> candidates = Collections.emptyList();
        final SCMRevisionAction sra = build.getAction(SCMRevisionAction.class);
        final CauseAction ca = build.getAction(CauseAction.class);

        if(candidates.isEmpty()) {
            if (sra != null && ca.findCause(Cause.UserIdCause.class) == null) {
                AccurevSCMHead head = (AccurevSCMHead) sra.getRevision().getHead();
                System.out.println("calculation revision for: " + head.getName() + " at transaction: " + head.getHash());
                candidates = getBuildChooser().getCandidateTransactions(false, getSingleStream(), ac , listener, buildData, head.getHash());
            } else {
                final String singleStream = environment.expand( getSingleStream() );
                candidates = getBuildChooser().getCandidateTransactions(false, singleStream, ac, listener, buildData);
                listener.getLogger().println(candidates.isEmpty());
            }
        }

        Build transToBuild;
        if(!candidates.isEmpty()) {
            AccurevTransaction markedTransaction = candidates.stream().max(Comparator.comparing(i -> i.getId())).get();
            AccurevStream stream = ac.fetchStream(getStreams().get(0).getDepot(), getSingleStream());
            transToBuild = new Build(stream, markedTransaction,  candidates, build.getNumber(), null);
            buildData.saveBuild(transToBuild);
        }else{
            AccurevTransaction markedTransaction = buildData.lastBuild == null ? null : buildData.lastBuild.transaction;
            AccurevStream stream = ac.fetchStream(getStreams().get(0).getDepot(), getSingleStream());
            transToBuild = new Build(stream, markedTransaction,  candidates, build.getNumber(), null);
            buildData.saveBuild(transToBuild);
        }

        /**
         *
         * If plugin attached to this job, fetch childstreams. Iterate through them and schedule a job for them.
         * Depending on level, get n-level children.
         *
         */
        Collection<AccurevStream> affectedStreams = newArrayList();
        for (AccurevSCMExtension ext : extensions) {
            Collection<AccurevStream> affected = ext.getAffectedToBuild(this, transToBuild, ac);
            if(!affected.isEmpty()) affectedStreams.addAll(affected);
        }
        if(!affectedStreams.isEmpty()) {
            ItemGroup folder = build.getParent().getParent();
            if (folder instanceof WorkflowMultiBranchProject) {
                WorkflowMultiBranchProject project = (WorkflowMultiBranchProject) folder;
                // For all workflow jobs do a potential trigger of item if it hasn't already been triggered
                project.allItems().forEach(obj -> {
                      if(obj instanceof WorkflowJob) {
                          WorkflowJob job = (WorkflowJob) obj;
                          AccurevSCM typicalSCM = (AccurevSCM) job.getTypicalSCM();
                          assert typicalSCM != null;
                          StreamSpec streamSpec = typicalSCM.streams.get(0);

                          // Find a potential match for our stream / depot combo
                          Optional<AccurevStream> hit = affectedStreams.stream().filter(o -> o.getName().equals(streamSpec.getName()) && o.getDepotName().equals(o.getDepotName())).findFirst();

                          // If we have a match, it means that there exists a stream in the MultiBranchProject with that name
                          if(hit.isPresent()) {
                              Optional upStreamCause = build.getCauses().stream().filter(x -> x.getClass().equals(Cause.UpstreamCause.class)).findFirst();
                              // If an upStreamCause is present in this build, it means that we were triggered from a parent stream so no need to double trigger a potential build
                              if(!upStreamCause.isPresent()) {
                                  job.scheduleBuild(new Cause.UpstreamCause(build));
                              }
                          }
                      }
                });
            }
        }
        return transToBuild;

    }

    @Nonnull
    @Override
    public String getKey() {
        StringBuilder builder = new StringBuilder("accurev");
        for (ServerRemoteConfig cfg : getServerRemoteConfigs()){
            builder.append(' ').append(cfg.getUri().toString());
        }
        return builder.toString();
    }

    public Object readResolve() {
        if(source != null) {
            serverRemoteConfigs = new ArrayList<>();
            streams = new ArrayList<>();

            String[] parts = source.split(":");
            StandardUsernamePasswordCredentials credentials = ac.getCredentials();
            if(credentials != null && (parts.length > 1)) {
                String id = credentials.getId();
                if(id != null) {
                    serverRemoteConfigs.add(new ServerRemoteConfig(parts[0], parts[1], id));
                }
                streams.add(new StreamSpec(""));
            }
        }

        if (extensions == null)
            extensions = new DescribableList<>(Saveable.NOOP);

        return this;
    }

    public BuildChooser getBuildChooser() {
        BuildChooser bc = new DefaultBuildChooser();
        bc.accurevSCM = this;
        return bc;
    }

    private String getSingleStream() {
        if(getStreams().size() != 1){
            return null;
        }
        String stream = getStreams().get(0).getName();
        return stream;
    }

    private void retrieveChanges(Run<?,?> build, AccurevClient ac, TaskListener listener) {
        final PrintStream log = listener.getLogger();
        /**
         * Fetches the Accurev Stream down, including all the files.
         * If Accurev stream is already down, fetch just the new updates
         * Should have all the newest updates included
         */
        if(build.getPreviousBuild() != null) {
            log.println("Fetching changes from Accurev stream");
        }else {
            log.println("Cloning the remote Accurev stream");
        }
    }

    private BuildData copyBuildData(Run<?,?> build) {
        BuildData base = getBuildData(build);
        if(base == null)
            return new BuildData("Accurev", getServerRemoteConfigs());
        else{
            BuildData buildData = base.clone();
            return buildData;
        }
    }

    private BuildData getBuildData(Run<?, ?> build) {
        BuildData buildData = null;
        while (build != null) {
            List<BuildData> buildDataList = build.getActions(BuildData.class);
            for (BuildData bd : buildDataList) {
                if (bd != null && isRelevantBuildData(bd)) {
                    buildData = bd;
                    break;
                }
            }
            if (buildData != null){
                break;
            }
            build = build.getPreviousBuild();
        }
        return buildData;
    }

    private boolean isRelevantBuildData(BuildData bd) {
        for(StreamSpec src : getStreams()) {
            if(bd.hasBeenReferenced(src.getName())){
                return true;
            }
        }
        return false;
    }

    @CheckForNull
    @Override
    public SCMRevisionState calcRevisionsFromBuild(@Nonnull Run<?, ?> build, @Nullable FilePath workspace, @Nullable Launcher launcher, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        EnvVars env = build.getEnvironment(listener);
//        if (workspace == null) {
//            throw new IOException("Workspace not specified");
//        }
        return SCMRevisionState.NONE;
    }

    @Override
    public SCMDescriptor<AccurevSCM> getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends SCMDescriptor<AccurevSCM> {

        public DescriptorImpl() {
            super(AccurevSCM.class, AccurevRepositoryBrowser.class);
            load();
        }



        @Override
        public SCM newInstance(@Nullable StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
            AccurevSCM scm = (AccurevSCM) super.newInstance(req,formData);
            scm.repositoryBrowser = RepositoryBrowsers.createInstance(
                    AccurevRepositoryBrowser.class,
                    req,
                    formData,
                    "browser");
            return scm;
        }

        public String getDisplayName(){
            return "Accurev";
        }

        @Override
        public boolean isApplicable(Job project) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindJSON(this, json);
            save();
            return true;
        }
    }
    private static final Logger LOGGER = Logger.getLogger(AccurevSCM.class.getName());
    @SuppressFBWarnings(value="MS_SHOULD_BE_FINAL", justification="Not final so users can adjust log verbosity")
    public static boolean VERBOSE = Boolean.getBoolean(AccurevSCM.class.getName() + ".verbose");
}
