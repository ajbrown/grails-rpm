import grails.util.Environment

class RpmGrailsPlugin {
    // the plugin version
    def version = "0.1"
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "2.1 > *"
    // the other plugins this plugin depends on
    def dependsOn = [:]

    def scopes = [excludes: 'war']
    def loadAfter = ['standalone']
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
        "grails-app/views/error.gsp"
    ]

    def title = "Grails Rpm Plugin" // Headline display name of the plugin
    def author = "A.J. Brown"
    def authorEmail = "aj@ajbrown.org"
    def description = '''\
This plugin allows the packaging of your grails application as an RPM which can be installed and run on your server.
 It leverages the standalone plugin to package the grails app as a jar with an embedded container.
'''
    // URL to the plugin's documentation
    def documentation = "https://github.com/ajbrown/grails-rpm"

    // License: one of 'APACHE', 'GPL2', 'GPL3'
    def license = "APACHE"
    def issueManagement = [ system: "JIRA", url: "https://github.com/ajbrown/grails-rpm/issues" ]
    def scm = [ url: "https://github.com/ajbrown/grails-rpm" ]
}
