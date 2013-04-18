import grails.util.Environment
import org.freecompany.redline.Builder
import org.freecompany.redline.header.Architecture
import org.freecompany.redline.header.Os
import org.freecompany.redline.header.RpmType

/* Copyright 2013 A.J. Brown <aj@ajbrown.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
includeTargets << grailsScript("_GrailsInit")
includeTargets << grailsScript('_GrailsPackage')
includeTargets << new File( standalonePluginDir, "scripts/BuildStandalone.groovy" )

target(rpmBuild: "package a grails application as an RPM") {
    depends compile, createConfig, loadPlugins

    //Merge in the default configuration
    def defaultConfig = new ConfigSlurper(Environment.current.name).parse(projectPackager.classLoader.loadClass("GrailsRpmPluginDefaultConfig"))
    config = defaultConfig.merge(config)
    def pluginConfig = config.grails.plugins.rpmBuild

    def fullName = "${grailsAppName}-${grailsAppVersion}"
    def jarName  = "rpm-build/${fullName}.jar"
    def targetPath = grailsSettings.projectTargetDir
    def rpmDir  = ""
    def javaVersion = config.grails.project.source.level

    //clean the rpm-build directory
    ant.delete( dir: "${targetPath}/rpm-build" )
    ant.mkdir( dir: "${targetPath}/rpm-build" )

    def release  = "01" //TODO release # can be tied to jenkins build #
    //TODO configurable
    def summary  = "Venueseen Galleries"
    def descript = "Venueseen Galleries Description."
    def license  = "Commercial"
    def url      = "http://venueseen.com"
    def vendor   = ""

    def programPrefix = "/usr/share/${grailsAppName}"
    def configPrefix  = "/etc/${grailsAppName}"
    def logPrefix     = "/var/log/${grailsAppName}"

    //Build the Jar using the standalone plugin
    event 'StatusUpdate', ["Building jar with Standalone plugin"]

    def workDir = new File( "${targetPath}/rpm-build" )
    ant.mkdir( dir: workDir.absolutePath )
    warfile = buildWar( workDir )
    def jarfile = new File( "${targetPath}/${jarName}" )
    if( !buildJar( workDir, jarfile, true, warfile ) ) {
        errorAndDie "Error building Jar file."
    }

    event 'StatusUpdate', ["Preparing files for packaging"]

    def rpmBuilder = new Builder()
    rpmBuilder.setPackage( grailsAppName, grailsAppVersion, release )
    rpmBuilder.type = RpmType.BINARY
    rpmBuilder.setPlatform( Architecture.NOARCH, Os.LINUX )

    rpmBuilder.addDependencyMore "java", javaVersion.toString()
    rpmBuilder.addDependencyMore "java", javaVersion.toString()
    //TODO allow adding other dependencies like redis

    //TODO Should we be specifying the group?  Configurable?
    rpmBuilder.group = "Applications/Internet"
    rpmBuilder.summary = summary
    rpmBuilder.description = descript
    rpmBuilder.license = license
    rpmBuilder.url = url ?: config.grails.serverURL
    rpmBuilder.vendor  = vendor ?: config.grails.project.groupId.toString()

    //Add our jar
    rpmBuilder.addFile "${programPrefix}/lib/${jarName}", jarfile

    //Create a script to run the jar file
    def runScript = new File( targetPath, "rpm-build/${grailsAppName}" )
    runScript.withWriter{ it << createRunScript( pluginConfig, programPrefix, jarName, logPrefix ) }
    rpmBuilder.addFile "${programPrefix}/bin/${grailsAppName}", runScript

    //Create an init script
    def initScript = new File( targetPath, "rpm-build/${grailsAppName}-control" )
    initScript.withWriter{ it << createInitScript( grailsAppName, programPrefix ) }
    rpmBuilder.addFile "${programPrefix}/bin/${grailsAppName}-control", initScript

    //Create main config file
    def mainConf = new File( targetPath, "rpm-build/${grailsAppName}.conf" )
    mainConf.withWriter{ it << createDefaultConfig( grailsAppName ) }
    rpmBuilder.addFile "/etc/sysconfig/${grailsAppName}", mainConf

    event 'StatusUpdate', ['Generating RPM']

    //Add pre and post install scripts
    rpmBuilder.setPostInstallScript( createPostInstallScript( programPrefix, grailsAppName ) )
    rpmBuilder.setPreUninstallScript( createPreUninstallScript( programPrefix, grailsAppName ) )

    def rpmPath = new File( grailsSettings.projectTargetDir, rpmDir )
    def rpmFile = rpmBuilder.build( rpmPath )
    event 'StatusUpdate', ["Done generating RPM target${rpmDir}/${rpmFile}"]

    return true
}

createRunScript = { ConfigObject config, String prefix, String jarName, String logPrefix ->
    """#!/bin/sh

HTTP_HOSTNAME="${config.http.hostname ?: '0.0.0.0'}"
HTTP_PORT="${config.http.port ?: 8080}"
HTTP_CONTEXT="${config.http.context ?: '/'}"
JAVA_OPTS="\$JAVA_OPTS -Xms${config.java.xms ?: '1024m'} -Xmx${config.java.xmx ?: '1024m'} -XX:MaxPermSize=${config.java.maxPermSize}"
JAR_PATH='$prefix/lib/$jarName'

# Check for missing binaries (stale symlinks should not happen)
test -r "\$JAR_PATH" || { echo "\$JAR_PATH not installed";
        if [ "\$1" = "stop" ]; then exit 0;
        else exit 5; fi; }


CMD="java \$JAVA_OPTS -jar \$JAR_PATH \$HTTP_CONTEXT \$HTTP_HOSTNAME \$HTTP_PORT"

\$CMD
""".trim()
}


createDefaultConfig = { String appName ->
    """
HTTP_HOSTNAME=0.0.0.0
HTTP_PORT=8080
HTTP_CONTEXT=""
#JAVA_OPTS=""
#JAVA_HOME=""
"""
}

createInitScript = { String appName, String prefix ->
    """
#!/bin/sh
#
# ${appName}      Start/Stop ${appName}.
#
# chkconfig: 35 80 20
# description: ${appName} standalone.
#
# processname: ${appName}
#
# By:
#
#
#
### BEGIN INIT INFO
# Provides:          ${appName}
# Required-Start:    \$local_fs \$remote_fs \$network \$time \$named
# Should-Start:      \$time sendmail
# Required-Stop:     \$local_fs \$remote_fs \$network \$time \$named
# Should-Stop:       \$time sendmail
# Default-Start:     3 5
# Default-Stop:      0 1 2 6
# Short-Description: ${appName}
# Description:       Starts ${appName}
### END INIT INFO

# Check for existence of needed config file and read it
DEFAULT_CONFIG=/etc/sysconfig/${appName}
LOCAL_CONFIG=/etc/${appName}/${appName}.conf
test -e "\$DEFAULT_CONFIG" || { echo "\$DEFAULT_CONFIG not existing";
        if [ "\$1" = "stop" ]; then exit 0;
        else exit 6; fi; }
test -r "\$DEFAULT_CONFIG" || { echo "\$DEFAULT_CONFIG not readable. Perhaps you forgot 'sudo'?";
        if [ "\$1" = "stop" ]; then exit 0;
        else exit 6; fi; }

# Override with local config, if existing
test -e "\$LOCAL_CONFIG" && . \$LOCAL_CONFIG




PID_FILE=/var/run/${appName}.pid
MY_USER=${appName}
MY_CMD=${prefix}/bin/${appName}
OUTPUT_LOG=/var/log/${appName}/output.log

# Source function library
. /etc/init.d/functions

# Get network config
. /etc/sysconfig/network

RETVAL=0


start() {
    DAEMON_EXEC="\${MY_CMD} >> \$OUTPUT_LOG"

    echo -n \$"Starting ${appName} "
    # Start me up!
    daemon --user="\$MY_USER" --pidfile="\$PID_FILE" "\$DAEMON_EXEC 2>&1" &
    RETVAL=\$?
        if [ \$RETVAL = 0 ]; then
            success
            sleep 1s
            echo > "\$PID_FILE"  # just in case we fail to find it
            MY_SESSION_ID=`/bin/ps h -o sess -p \$\$`
            # get PID
            /bin/ps hww -u "\$MY_USER" -o sess,ppid,pid,cmd | \
            while read sess ppid pid cmd; do
                [ "\$ppid" = 1 ] || continue
                echo "\$cmd" | grep "\$DAEMON_EXEC"
                [ \$? = 0 ] || continue
                # found a PID
                echo \$pid > "\$PID_FILE"
            done
        else
            failure
        fi

    echo
    [ \$RETVAL -eq 0 ] && touch /var/lock/subsys/${appName}
    return \$RETVAL
}

stop() {
    echo -n \$"Stopping ${appName} "
    killproc -p \$PID_FILE
    RETVAL=\$?
    echo
    [ \$RETVAL -eq 0 ] && rm -f /var/lock/subsys/${appName}
    return \$RETVAL
}

restart() {
      stop
    start
}

reload() {
    stop
    start
}

case "\$1" in
  start)
      start
    ;;
  stop)
      stop
    ;;
  status)
    status ${appName}
    ;;
  restart)
      restart
    ;;
  condrestart)
      [ -f /var/lock/subsys/${appName} ] && restart || :
    ;;
  reload)
    reload
    ;;
  *)
    echo \$"Usage: \$0 {start|stop|status|restart|condrestart}"
    exit 1
esac

exit \$?
""".trim()

}

createPostInstallScript = { String prefix, String appName ->
    """
useradd -m -d ${prefix} -s /sbin/nologin ${appName}
chown -R ${appName}:${appName} ${prefix}
chmod 544 ${prefix}/bin/*
mkdir -p /var/log/${appName}
touch /var/log/${appName}/output.log /var/log/${appName}/stacktrace.log
chown -R ${appName}:${appName} /var/log/${appName}
chmod u+w -R /var/log/${appName}
ln -sf ${prefix}/bin/${appName}-control /etc/init.d/${appName}
chkconfig --add ${appName}
/etc/init.d/${appName} start
""".trim()
}

createPreUninstallScript = { prefix, appName ->
    """
/etc/init.d/${appName} stop
chkconfig --del ${appName}
unlink /etc/init.d/${appName}
userdel ${appName}
""".trim()
}

setDefaultTarget(rpmBuild)

