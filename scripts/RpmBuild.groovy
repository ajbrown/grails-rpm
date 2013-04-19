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
    def jarName  = "${fullName}.jar"
    def targetPath = "${grailsSettings.projectTargetDir}/rpm-build"
    def rpmDir  = ""
    def javaVersion = config.grails.project.source.level

    //clean the rpm-build directory
    ant.delete( dir: targetPath )
    ant.mkdir( dir: targetPath )

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

    def workDir = new File( targetPath )
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
    def runScript = new File( targetPath, grailsAppName )
    runScript.withWriter{ it << createRunScript( pluginConfig, programPrefix, fullName, grailsAppName ) }
    rpmBuilder.addFile "${programPrefix}/bin/${grailsAppName}", runScript

    //Create an init script
    def initScript = new File( targetPath, "${grailsAppName}-control" )
    initScript.withWriter{ it << createInitScript( grailsAppName, jarName, programPrefix ) }
    rpmBuilder.addFile "${programPrefix}/bin/${grailsAppName}-control", initScript

    //Create main config file
    def mainConf = new File( targetPath, "${grailsAppName}.conf" )
    mainConf.withWriter{ it << createDefaultConfig( grailsAppName, pluginConfig ) }
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

createRunScript = { ConfigObject config, String prefix, String jarName, String appName ->
    """#!/bin/sh

. /etc/sysconfig/${appName}
JAR_PATH='$prefix/lib/$jarName'

# Check for missing binaries (stale symlinks should not happen)
test -r "\$JAR_PATH" || { echo "\$JAR_PATH not installed"; exit 5; fi; }

java \$JAVA_OPTS -jar \$JAR_PATH \$HTTP_CONTEXT \$HTTP_HOSTNAME \$HTTP_PORT
""".trim()
}


createDefaultConfig = { String appName, ConfigObject config ->
    """
HTTP_HOSTNAME="${config.http.hostname ?: '0.0.0.0'}"
HTTP_PORT=${config.http.port ?: '8080'}
HTTP_CONTEXT="${config.http.context ?: '/'}"
JAVA_OPTS="${config.java.opts ?: ''}"
#JAVA_HOME=""
"""
}

createInitScript = { String appName, String jarName, String prefix ->
    """
#!/bin/bash
#
# chkconfig: 345 90 10
# description: Java deamon script
#
# A non-SUSE Linux start/stop script for Java daemons.
#
# Derived from - http://shrubbery.mynetgear.net/c/display/W/Java+Daemon+Startup+Script

# Load configuration options
. /etc/sysconfig/${appName}

serviceNameLo="${appName}"                                  # service name with the first letter in lowercase
serviceName="${appName}"                                    # service name
serviceUser="${appName}"                                      # OS user name for the service
serviceGroup="${appName}"                                    # OS group name for the service
applDir="${prefix}"                          # home directory of the service application
serviceUserHome="\$applDir"                       # home directory of the service user
serviceLogFile="/var/log/\$serviceNameLo/\$serviceNameLo.log"               # log file for StdOut/StdErr
maxShutdownTime=15                                         # maximum number of seconds to wait for the daemon to terminate normally
pidFile="/var/run/\$serviceNameLo.pid"                      # name of PID file (PID = process ID number)
javaCommand="java"                                         # name of the Java launcher without the path
javaExe="java"                                        # file name of the Java application launcher executable
javaArgs="\$JAVA_OPTS -jar ${prefix}/lib/${jarName} \$HTTP_CONTEXT \$HTTP_HOSTNAME \$HTTP_PORT"
javaCommandLine="\$javaExe \$javaArgs"                       # command line to start the Java service application
javaCommandLineKeyword="${jarName}"                     # a keyword that occurs on the commandline, used to detect an already running service process and to distinguish it from others

# Makes the file \$1 writable by the group \$serviceGroup.
function makeFileWritable {
   local filename="\$1"
   touch \$filename || return 1
   chgrp \$serviceGroup \$filename || return 1
   chmod g+w \$filename || return 1
   return 0; }

# Returns 0 if the process with PID \$1 is running.
function checkProcessIsRunning {
   local pid="\$1"
   if [ -z "\$pid" -o "\$pid" == " " ]; then return 1; fi
   if [ ! -e /proc/\$pid ]; then return 1; fi
   return 0; }

# Returns 0 if the process with PID \$1 is our Java service process.
function checkProcessIsOurService {
   local pid="\$1"
   if [ "\$(ps -p \$pid --no-headers -o comm)" != "\$javaCommand" ]; then return 1; fi
   grep -q --binary -F "\$javaCommandLineKeyword" /proc/\$pid/cmdline
   if [ \$? -ne 0 ]; then return 1; fi
   return 0; }

# Returns 0 when the service is running and sets the variable \$pid to the PID.
function getServicePID {
   if [ ! -f \$pidFile ]; then return 1; fi
   pid="\$(<\$pidFile)"
   checkProcessIsRunning \$pid || return 1
   checkProcessIsOurService \$pid || return 1
   return 0; }

function startServiceProcess {
   cd \$applDir || return 1
   rm -f \$pidFile
   makeFileWritable \$pidFile || return 1
   makeFileWritable \$serviceLogFile || return 1
   cmd="nohup \$javaCommandLine >>\$serviceLogFile 2>&1 &"
   su -m \$serviceUser -s \$SHELL -c "\$cmd" || return 1
   sleep 1s
   ps hww -U \$serviceUser -o sess,ppid,pid,cmd | grep "\$javaCommandLineKeyword" | while read sess ppid pid cmd; do \
        [[ "\$ppid" -eq "1" ]] || continue; \
        echo "\$pid" > \$pidFile; \
        done;
   pid="\$(<\$pidFile)"
   if checkProcessIsRunning \$pid; then :; else
      echo -ne "\n\$serviceName start failed, see logfile."
      return 1
   fi
   return 0; }

function stopServiceProcess {
   kill \$pid || return 1
   for ((i=0; i<maxShutdownTime*10; i++)); do
      checkProcessIsRunning \$pid
      if [ \$? -ne 0 ]; then
         rm -f \$pidFile
         return 0
         fi
      sleep 0.1
      done
   echo -e "\n\$serviceName did not terminate within \$maxShutdownTime seconds, sending SIGKILL..."
   kill -s KILL \$pid || return 1
   local killWaitTime=15
   for ((i=0; i<killWaitTime*10; i++)); do
      checkProcessIsRunning \$pid
      if [ \$? -ne 0 ]; then
         rm -f \$pidFile
         return 0
         fi
      sleep 0.1
      done
   echo "Error: \$serviceName could not be stopped within \$maxShutdownTime+\$killWaitTime seconds!"
   return 1; }

function startService {
   getServicePID
   if [ \$? -eq 0 ]; then echo -n "\$serviceName is already running"; RETVAL=0; return 0; fi
   echo -n "Starting \$serviceName   "
   startServiceProcess
   if [ \$? -ne 0 ]; then RETVAL=1; echo "failed"; return 1; fi
   echo "started PID=\$pid"
   RETVAL=0
   return 0; }

function stopService {
   getServicePID
   if [ \$? -ne 0 ]; then echo -n "\$serviceName is not running"; RETVAL=0; echo ""; return 0; fi
   echo -n "Stopping \$serviceName   "
   stopServiceProcess
   if [ \$? -ne 0 ]; then RETVAL=1; echo "failed"; return 1; fi
   echo "stopped PID=\$pid"
   RETVAL=0
   return 0; }

function checkServiceStatus {
   echo -n "Checking for \$serviceName:   "
   if getServicePID; then
    echo "running PID=\$pid"
    RETVAL=0
   else
    echo "stopped"
    RETVAL=3
   fi
   return 0; }

function main {
   RETVAL=0
   case "\$1" in
      start)                                               # starts the Java program as a Linux service
         startService
         ;;
      stop)                                                # stops the Java program service
         stopService
         ;;
      restart)                                             # stops and restarts the service
         stopService && startService
         ;;
      status)                                              # displays the service status
         checkServiceStatus
         ;;
      *)
         echo "Usage: \$0 {start|stop|restart|status}"
         exit 1
         ;;
      esac
   exit \$RETVAL
}

main \$1
"""
}

createPostInstallScript = { String prefix, String appName ->
    """
useradd -m -d ${prefix} -s /sbin/nologin ${appName}
chown -R ${appName}:${appName} ${prefix}
chmod 544 ${prefix}/bin/*
mkdir -p /var/log/${appName}
touch /var/log/${appName}/${appName}.log /var/log/${appName}/stacktrace.log
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
rm /var/run/campaign-galleries.pid
""".trim()
}

setDefaultTarget(rpmBuild)

