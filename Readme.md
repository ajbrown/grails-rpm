
# TODO

## High Priority
- Documentation
- Improvements to init script
- rpm spec configurable in grails config (vendor, url, architecture, os).
- add additional dependencies via grails config
- add pre/postun script lines via grails config
- configurable runtime user
- grailsy configurable main configuration file

## Medium Priority
- Tests?  (This is a build tool, is there a good way to test?)
- rpm-publish to publish to a RPM repository
- init script templating (bring your own init script, supply correct template variables)
- add additional post/preun script lines via grails config

## Low Priority
- build for different architectures.
- MacOSX, FreeBSD, and Solaris builds.
- source RPM packaged with grails wrapper.
- Remove dependency on standalone plugin's targets?  Seems very fragile.