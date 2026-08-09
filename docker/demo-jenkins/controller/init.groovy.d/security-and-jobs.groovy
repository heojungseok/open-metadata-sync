import java.nio.file.Files
import java.nio.file.Path

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import hudson.EnvVars
import hudson.model.Item
import hudson.model.JDK
import hudson.model.Node
import hudson.model.ChoiceParameterDefinition
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import hudson.model.User
import hudson.model.View
import hudson.security.ProjectMatrixAuthorizationStrategy
import hudson.security.AuthorizationMatrixProperty
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.csrf.DefaultCrumbIssuer
import hudson.slaves.DumbSlave
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.slaves.RetentionStrategy
import hudson.plugins.sshslaves.SSHLauncher
import jenkins.model.Jenkins
import jenkins.model.JenkinsLocationConfiguration
import jenkins.install.InstallState
import org.jenkinsci.plugins.matrixauth.PermissionEntry
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()
jenkins.setNumExecutors(0)
jenkins.setMode(Node.Mode.EXCLUSIVE)
def securityRealm = new HudsonPrivateSecurityRealm(false, false, null)
jenkins.setSecurityRealm(securityRealm)
if (User.getById('bootstrap-disabled', false) == null) {
    securityRealm.createAccount('bootstrap-disabled', UUID.randomUUID().toString())
}
jenkins.setInstallState(InstallState.RUNNING)
jenkins.setCrumbIssuer(new DefaultCrumbIssuer(true))
jenkins.setSlaveAgentPort(-1)
jenkins.setQuietPeriod(0)

def jdkDescriptor = jenkins.getDescriptorByType(JDK.DescriptorImpl)
jdkDescriptor.setInstallations(new JDK('jdk21', '/opt/java/openjdk'))

def globalAuthorization = new ProjectMatrixAuthorizationStrategy()
globalAuthorization.add(Jenkins.READ, PermissionEntry.group('anonymous'))
globalAuthorization.add(View.READ, PermissionEntry.group('anonymous'))
jenkins.setAuthorizationStrategy(globalAuthorization)

def location = JenkinsLocationConfiguration.get()
location.setUrl('https://demo.heojungseok.com/')

def globalEnvironment = new EnvironmentVariablesNodeProperty(
        new EnvironmentVariablesNodeProperty.Entry('DEMO_RUNTIME', 'container'),
        new EnvironmentVariablesNodeProperty.Entry('DEMO_SOURCE_DIR', '/opt/open-metadata-sync'),
        new EnvironmentVariablesNodeProperty.Entry('DEMO_REVISION', '47461be71ae4add166b5a5ea157465c370894330'),
        new EnvironmentVariablesNodeProperty.Entry('DB_HOST', 'mysql'),
        new EnvironmentVariablesNodeProperty.Entry('DB_PORT', '3306'),
        new EnvironmentVariablesNodeProperty.Entry(
                'DEMO_REPLAY_SOURCE_EXECUTION_ID', '00000000-0000-0000-0000-00000000d001')
)
jenkins.getGlobalNodeProperties().replaceBy([globalEnvironment])

def credentials = SystemCredentialsProvider.getInstance().getStore()
def sshKey = Files.readString(Path.of('/run/secrets/agent_ssh_key')).trim()
def databasePassword = Files.readString(Path.of('/run/secrets/demo_mysql_password')).trim()

if (credentials.getCredentials(Domain.global()).find { it.id == 'demo-agent-ssh' } == null) {
    credentials.addCredentials(Domain.global(), new BasicSSHUserPrivateKey(
            CredentialsScope.GLOBAL,
            'demo-agent-ssh',
            'jenkins',
            new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(sshKey),
            '',
            'Dedicated public demo agent key'))
}
if (credentials.getCredentials(Domain.global()).find { it.id == 'open-metadata-sync-db' } == null) {
    credentials.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(
            CredentialsScope.GLOBAL,
            'open-metadata-sync-db',
            'Dedicated public demo MySQL',
            'open_metadata',
            databasePassword))
}

def demoNode = jenkins.getNode('demo-agent')
if (demoNode == null) {
    def launcher = new SSHLauncher('jenkins-agent', 22, 'demo-agent-ssh')
    demoNode = new DumbSlave('demo-agent', '/home/jenkins/agent', launcher)
    jenkins.addNode(demoNode)
}
demoNode.setLabelString('demo-agent')
demoNode.setNumExecutors(1)
demoNode.setMode(Node.Mode.NORMAL)
demoNode.setRetentionStrategy(RetentionStrategy.INSTANCE)
demoNode.save()

def createPublicJob = { String name, String scriptName, String description, List parameterDefinitions ->
    def job = jenkins.getItem(name)
    if (job == null) {
        job = jenkins.createProject(WorkflowJob, name)
    }
    job.setDescription(description)
    job.setDefinition(new CpsFlowDefinition(
            Files.readString(Path.of('/opt/demo-pipelines/' + scriptName)), true))
    job.removeProperty(ParametersDefinitionProperty)
    job.addProperty(new ParametersDefinitionProperty(parameterDefinitions))
    job.removeProperty(AuthorizationMatrixProperty)
    def authorization = new AuthorizationMatrixProperty()
    authorization.add(Item.READ, PermissionEntry.group('anonymous'))
    authorization.add(Item.BUILD, PermissionEntry.group('anonymous'))
    job.addProperty(authorization)
    job.save()
}

createPublicJob(
        'open-metadata-sync-demo-10k',
        'Jenkinsfile.demo',
        'Isolated 10K INITIAL and NO_OP demonstration',
        [
                new StringParameterDefinition('REQUEST_ID', '', 'Server-generated public request identifier'),
                new ChoiceParameterDefinition('DEMO_SCENARIO', ['INITIAL', 'NO_OP'] as String[], 'Demo scenario'),
                new StringParameterDefinition('SEED', '20260809', 'Fixed deterministic seed'),
                new ChoiceParameterDefinition('CHUNK_SIZE', ['100', '500', '1000', '2000'] as String[], 'Approved chunk size')
        ])
createPublicJob(
        'open-metadata-sync-demo-replay',
        'Jenkinsfile.crossref',
        'Repeatable REPLAY_ERRORS before and after demonstration',
        [
                new StringParameterDefinition('REQUEST_ID', '', 'Server-generated public request identifier'),
                new ChoiceParameterDefinition('MODE', ['REPLAY_ERRORS'] as String[], 'Fixed public mode'),
                new StringParameterDefinition('CREATED_FROM', '', ''),
                new StringParameterDefinition('CREATED_UNTIL', '', ''),
                new StringParameterDefinition('MAX_ITEMS', '', ''),
                new StringParameterDefinition('SOURCE_NAME', 'crossref', ''),
                new StringParameterDefinition('BOOTSTRAP_INDEXED_FROM', '', ''),
                new StringParameterDefinition('INDEXED_FROM_UTC', '', ''),
                new StringParameterDefinition('INDEXED_UNTIL_UTC', '', ''),
                new StringParameterDefinition('SOURCE_EXECUTION_ID', '00000000-0000-0000-0000-00000000d001', 'Fixed fixture'),
                new StringParameterDefinition('CHUNK_SIZE', '1000', 'Fixed public chunk size'),
                new StringParameterDefinition('HIBERNATE_BATCH_SIZE', '1000', 'Fixed public batch size')
        ])

jenkins.save()
