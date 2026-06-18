// =============================================================================
// bind-sca-tokens.groovy  -  system (trusted) Groovy script, NOT Job DSL
// =============================================================================
// Binds each org's Veracode SCA token as a folder-scoped credential with the
// fixed id 'srcclr-api-token', so the shared library resolves the right token
// for that org. This closes the gap the seed leaves open: the Job DSL seed
// cannot create credentials (sandboxed, and secrets must be encrypted via the
// Credentials API), so the actual binding happens here.
//
// Run AFTER the folder seed has created the org folders. Idempotent: updates the
// credential if it exists, adds it if not, so re-running (e.g. after adding an
// org) is safe.
//
// Token values are read from controller environment variables, one per org,
// named SRCCLR_TOKEN_<ORG> with the org name upper-cased and '-' replaced by '_'
// (org 'acme-corp' -> SRCCLR_TOKEN_ACME_CORP). Inject these the same way as the
// other Jenkins secrets. Do NOT hardcode token values in this file.
//
// Class/constructor names below are plugin-version sensitive (cloudbees-folder,
// plain-credentials, credentials). Verify against the installed versions.
// =============================================================================

import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.AbstractFolder
import com.cloudbees.hudson.plugins.folder.Folder
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider.FolderCredentialsProperty
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.domains.DomainCredentials
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.util.Secret

final String CRED_ID = 'srcclr-api-token'   // fixed id the library reads
final String PARENT  = 'veracode'           // parent folder holding the org folders

def storeFor(AbstractFolder folder) {
    def prop = folder.getProperties().get(FolderCredentialsProperty)
    if (prop == null) {
        prop = new FolderCredentialsProperty([] as DomainCredentials[])
        folder.addProperty(prop)
    }
    return prop.getStore()
}

def upsert(AbstractFolder folder, String id, String value) {
    def store = storeFor(folder)
    def cred = new StringCredentialsImpl(
        CredentialsScope.GLOBAL, id,
        "Veracode SCA token for ${folder.fullName}", Secret.fromString(value))
    def existing = store.getCredentials(Domain.global()).find { it.id == id }
    if (existing != null) {
        store.updateCredentials(Domain.global(), existing, cred)
        return 'updated'
    }
    store.addCredentials(Domain.global(), cred)
    return 'added'
}

def parent = Jenkins.get().getItemByFullName(PARENT, Folder)
if (parent == null) {
    println "[bind-sca-tokens] parent folder '${PARENT}' not found; run the folder seed first."
    return
}

parent.getItems().findAll { it instanceof AbstractFolder }.each { folder ->
    def envName = 'SRCCLR_TOKEN_' + folder.name.toUpperCase().replace('-', '_')
    def token = System.getenv(envName)
    if (token == null || token.trim().isEmpty()) {
        println "[bind-sca-tokens] ${folder.fullName}: ${envName} not set; skipped."
        return
    }
    def action = upsert(folder, CRED_ID, token.trim())
    folder.save()
    println "[bind-sca-tokens] ${folder.fullName}: ${CRED_ID} ${action}."
}
