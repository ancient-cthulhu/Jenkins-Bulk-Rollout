// =============================================================================
// veracode-onboard.groovy  -  single trusted (system) Groovy script
// =============================================================================
// One run does everything, for every org in ORGS:
//   1. Ensure the parent 'veracode' folder and one Organization Folder per org.
//   2. Find-or-create that org's Veracode SCA workspace.
//   3. Find-or-create a Jenkins-only SCA agent and regenerate its token.
//   4. Upsert that token as the folder credential 'srcclr-api-token'.
//   5. Apply NoTriggerOrganizationFolderProperty so discovery only auto-builds
//      main/master -- PR branches and feature branches are registered but never
//      auto-queued, preventing executor starvation and stale pending checks.
//
// Run as a SYSTEM (trusted) script: Manage Jenkins > Script Console for a
// one-off, or a freestyle admin job to repeat/schedule. NOT Job DSL, NOT
// sandboxed pipeline.
//
// Needs: egress to api.veracode.com; 'veracode-api-id' / 'veracode-api-key'
// in the Jenkins credential store; the 'scm-readonly' GitHub credential.
//
// Adding an org = add its name to ORGS and re-run. Idempotent.
// =============================================================================

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

import jenkins.model.Jenkins
import hudson.security.ACL
import hudson.util.Secret

import com.cloudbees.hudson.plugins.folder.Folder
import com.cloudbees.hudson.plugins.folder.AbstractFolder
import com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger
import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider.FolderCredentialsProperty

import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.domains.DomainCredentials
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl

import jenkins.branch.OrganizationFolder
import jenkins.branch.NoTriggerOrganizationFolderProperty
import org.jenkinsci.plugins.github_branch_source.GitHubSCMNavigator
import org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectFactory

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

// ---- config -----------------------------------------------------------------
@Field List<String> ORGS = [
    'Veracode-CSE-Demos',
    // add more orgs here and re-run
]

@Field final String PARENT_FOLDER   = 'veracode'
@Field final String SCM_CRED_ID     = 'scm-readonly'
@Field final String SCA_CRED_ID     = 'srcclr-api-token'
@Field final String VC_API_ID_CRED  = 'veracode-api-id'
@Field final String VC_API_KEY_CRED = 'veracode-api-key'
@Field final String VC_HOST         = 'api.veracode.com'
@Field final String GITHUB_API_URI  = 'https://api.github.com'
@Field final String HMAC_SCHEME     = 'VERACODE-HMAC-SHA-256'
@Field final String REQ_VERSION     = 'vcode_request_version_1'

@Field String vcId  = readSecretText(VC_API_ID_CRED)
@Field String vcKey = readSecretText(VC_API_KEY_CRED)

// ---- credential store read --------------------------------------------------
String readSecretText(String id) {
    def all = CredentialsProvider.lookupCredentials(
        StringCredentials, Jenkins.get(), ACL.SYSTEM, Collections.emptyList())
    def c = all.find { it.id == id }
    if (c == null) throw new RuntimeException("Credential '${id}' not found in the store")
    return c.secret.plainText
}

// ---- Veracode HMAC signing --------------------------------------------------
byte[] hmac(byte[] key, byte[] msg) {
    def mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(key, 'HmacSHA256'))
    return mac.doFinal(msg)
}

byte[] hexToBytes(String s) {
    byte[] out = new byte[(int) (s.length() / 2)]
    for (int i = 0; i < s.length(); i += 2)
        out[(int) (i / 2)] = (byte) Integer.parseInt(s.substring(i, i + 2), 16)
    return out
}

String toHex(byte[] b) { b.collect { String.format('%02x', it) }.join() }

String authHeader(String method, String urlPath) {
    byte[] keyBytes = hexToBytes(vcKey)
    byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce)
    long ts = System.currentTimeMillis()
    String data = "id=${vcId}&host=${VC_HOST}&url=${urlPath}&method=${method.toUpperCase()}"
    byte[] kN  = hmac(keyBytes, nonce)
    byte[] kT  = hmac(kN, ts.toString().getBytes('UTF-8'))
    byte[] kV  = hmac(kT, REQ_VERSION.getBytes('UTF-8'))
    byte[] sig = hmac(kV, data.getBytes('UTF-8'))
    return "${HMAC_SCHEME} id=${vcId},ts=${ts},nonce=${toHex(nonce)},sig=${toHex(sig)}"
}

String enc(String s) { URLEncoder.encode(s, 'UTF-8').replace('+', '%20') }

Map vc(String method, String urlPath, Object body = null) {
    def conn = (HttpURLConnection) new URL("https://${VC_HOST}${urlPath}").openConnection()
    conn.requestMethod = method
    conn.setRequestProperty('Authorization', authHeader(method, urlPath))
    conn.setRequestProperty('Accept', 'application/json')
    conn.connectTimeout = 30000
    conn.readTimeout = 60000
    if (body != null) {
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.doOutput = true
        conn.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(body) }
    }
    int code = conn.responseCode
    String text = (code >= 400 ? conn.errorStream : conn.inputStream)?.getText('UTF-8') ?: ''
    return [code: code, json: (text ? new JsonSlurper().parseText(text) : null), text: text]
}

// ---- Veracode: workspace + agent + token ------------------------------------
String findWorkspaceId(String org) {
    int page = 0
    while (true) {
        def r = vc('GET', "/srcclr/v3/workspaces?filter%5Bworkspace%5D=${enc(org)}&size=100&page=${page}")
        if (r.code != 200) throw new RuntimeException("list workspaces ${org}: ${r.code} ${r.text}")
        def hit = (r.json?._embedded?.workspaces ?: []).find { it.name == org }
        if (hit?.id) return hit.id
        int totalPages = (r.json?.page?.total_pages ?: 1) as int
        if (page >= totalPages - 1) return null
        page++
    }
}

String ensureWorkspace(String org) {
    def id = findWorkspaceId(org)
    if (id) return id
    def r = vc('POST', '/srcclr/v3/workspaces', [name: org])
    if (!(r.code in [200, 201])) throw new RuntimeException("create workspace ${org}: ${r.code} ${r.text}")
    for (int i = 0; i < 3; i++) { id = findWorkspaceId(org); if (id) return id; sleep(1000) }
    throw new RuntimeException("workspace ${org} created but not found after retries")
}

String jenkinsAgentName(String org) {
    String suffix = '-jenkins'
    int maxOrg = 20 - suffix.length()
    String t = org.length() > maxOrg ? org.substring(0, maxOrg) : org
    if (!t || !Character.isLetter(t.charAt(0))) {
        t = 'gh' + t
        if (t.length() > maxOrg) t = t.substring(0, maxOrg)
    }
    return t + suffix
}

String freshJenkinsToken(String wsId, String org) {
    String name = jenkinsAgentName(org)
    def lr = vc('GET', "/srcclr/v3/workspaces/${wsId}/agents")
    if (lr.code != 200) throw new RuntimeException("list agents ${org}: ${lr.code} ${lr.text}")
    def existing = (lr.json?._embedded?.agents ?: []).find { it.name == name }
    if (existing?.id) {
        def rr = vc('POST', "/srcclr/v3/workspaces/${wsId}/agents/${existing.id}/token:regenerate")
        if (rr.code != 200) throw new RuntimeException("regenerate ${org}: ${rr.code} ${rr.text}")
        def tok = rr.json?.access_token
        if (!tok) throw new RuntimeException("regenerate ${org}: no access_token in response")
        return tok
    }
    def cr = vc('POST', "/srcclr/v3/workspaces/${wsId}/agents", [name: name, agent_type: 'CLI'])
    if (cr.code != 200) throw new RuntimeException("create agent ${org}: ${cr.code} ${cr.text}")
    def tok = cr.json?.token?.access_token
    if (!tok) throw new RuntimeException("create agent ${org}: no token.access_token in response")
    return tok
}

// ---- Jenkins: folder + credential -------------------------------------------
Folder ensureParent() {
    def existing = Jenkins.get().getItem(PARENT_FOLDER)
    if (existing instanceof Folder) return existing
    if (existing != null) throw new RuntimeException("'${PARENT_FOLDER}' exists but is not a plain folder")
    return Jenkins.get().createProject(Folder, PARENT_FOLDER)
}

OrganizationFolder ensureOrgFolder(Folder parent, String org) {
    def existing = parent.getItem(org)
    if (existing instanceof OrganizationFolder) return existing
    if (existing != null) throw new RuntimeException("'${PARENT_FOLDER}/${org}' exists but is not an org folder")

    def of = parent.createProject(OrganizationFolder, org)
    of.description = "Veracode scanning for all ${org} repositories"

    def nav = new GitHubSCMNavigator(org)
    nav.apiUri = GITHUB_API_URI
    nav.credentialsId = SCM_CRED_ID
    nav.traits = [new BranchDiscoveryTrait(3), new OriginPullRequestDiscoveryTrait(1)]
    of.navigators.replace(nav)

    of.projectFactories.replace(new WorkflowMultiBranchProjectFactory())
    of.addTrigger(new PeriodicFolderTrigger('1d'))
    of.orphanedItemStrategy = new DefaultOrphanedItemStrategy(true, '7', '50')
    of.save()
    return of
}

// Always applied whether the folder is new or already exists.
// On discovery, only auto-build main/master -- all other branches are
// registered as jobs but never automatically queued.
void ensureNoTriggerProperty(OrganizationFolder of) {
    of.getProperties().removeIf { it instanceof NoTriggerOrganizationFolderProperty }
    of.addProperty(new NoTriggerOrganizationFolderProperty('^(main|master)$'))
    of.save()
}

void upsertFolderToken(AbstractFolder folder, String id, String value) {
    def prop = folder.getProperties().get(FolderCredentialsProperty)
    if (prop == null) {
        prop = new FolderCredentialsProperty([] as DomainCredentials[])
        folder.addProperty(prop)
    }
    def store = prop.store
    def cred = new StringCredentialsImpl(
        CredentialsScope.GLOBAL, id, "Veracode SCA token for ${folder.fullName}", Secret.fromString(value))
    def existing = store.getCredentials(Domain.global()).find { it.id == id }
    if (existing != null) store.updateCredentials(Domain.global(), existing, cred)
    else                  store.addCredentials(Domain.global(), cred)
    folder.save()
}

// ---- run --------------------------------------------------------------------
def pf = vc('GET', '/srcclr/v3/workspaces?size=1&page=0')
if (pf.code != 200)
    throw new RuntimeException("Veracode preflight failed (${pf.code}). Check veracode-api-id/key and signing. Body: ${pf.text}")

def parent = ensureParent()
int ok = 0, failed = 0
ORGS.each { org ->
    try {
        def of    = ensureOrgFolder(parent, org)
        ensureNoTriggerProperty(of)
        def wsId  = ensureWorkspace(org)
        def token = freshJenkinsToken(wsId, org)
        upsertFolderToken(of, SCA_CRED_ID, token)
        try { of.scheduleBuild() } catch (ignored) { }
        println "[veracode-onboard] ${PARENT_FOLDER}/${org}: ok, token rotated, '${SCA_CRED_ID}' set."
        ok++
    } catch (e) {
        println "[veracode-onboard] ${org}: FAILED - ${e.message}"
        failed++
    }
}
println "[veracode-onboard] done: ${ok} ok, ${failed} failed, ${ORGS.size()} total."
