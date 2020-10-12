The Noregian TOOP DC/DP (The Once-Only Principle, Data Consumer/Data Provider) uses [toop-connector-ng](https://github.com/TOOP4EU/toop-connector-ng) and its built-in in-memory AS4 (phase4).

# toop-smp
toop-smp is basically the dockerimage phelger/phoss-smp-xml:latest rebuilt with Norwegian settings.
When building with "mvn clean install", dockerfile will copy keystore ans truststore into /config and generate the quay.apps.ocp-svc.base.brreg.no/toop/toop-smp:latest docker image. The actual config files, which contains passwords, are stored as OpenShift Secrets. These insists on being unpacked to unique directories, which explains why dockerfile sets environment variables for the config files to some mysterious paths. When deploying toop-smp:latest to OpenShift, Secrets are unpacked to these directories.

_(For internal use: The OpenShift regsys-prd environment, including Secrets, is defined in https://bitbucket.brreg.no/scm/openshift-appconfig/toop.git . After modifying a property file, execute regsys-prd/-/secrets/krypter.sh to regenerate Secrets)_

# toop-connector
toop-connector uses tc-main and tc-mem-phase4 from toop-connector-ng to implement the Norwegian DC and DP. Organization information is fetched using the Enhetsregisteret REST API (and cached in 1000 MRU)

The code is spec-first, meaning the first thing happening at "mvn clean install" is that resources/openAPI/toop.yaml is used for generating Java code (POJO and REST API endpoints). (ApplicationInfo.java and banner.txt will also be generated, mostly to help log which versions are in use)

At startup (in Application.java), the AS4 servlet is registered and UniRest (REST Client) and TOOP Connector is initialized. After this, the application wait for:
- a Norwegian organization uses it as Data Consumer by calling one of the endpoints in QueryApiImpl
- one of the pending DCs get a response (BrregIncomingHandler.handleIncomingResponse)
- an European organization uses it as a Data Provider (BrregIncomingHandler.handleIncomingRequest)

As noted, the Norwegian DC is exposed as a REST API. However, as part of building the application, a Swagger UI is generated. For basic testing or curiosity, check it out at https://toop-connector.brreg.no/swagger-ui.html

_(For internal use: To build and deploy the application, please see either the top-level build.sh that builds both toop-smp and toop-connector, or the toop-connector/build.sh that builds and deploys only toop-connector_

_dockerimages will be uploaded to https://quay.apps.ocp-svc.base.brreg.no/organization/toop_

_OpenShift pods are available at https://console-openshift-console.apps.ocp-prd.regsys.brreg.no/k8s/ns/toop/pods . Select a pod to see its log console)_

## The properties files
The properties files are stored as OpenShift Secrets. The files, without passwords, looks like this:
### For toop-smp:
#### smp-server.properties
```
smp.backend = xml
smp.keystore.type = jks
smp.keystore.path = /config/toop-keystore.jks
smp.keystore.password = <password>
smp.keystore.key.alias = brc
smp.keystore.key.password = <password>
smp.truststore.type = jks
smp.truststore.path = /config/playground-truststore-v4.1.jks
smp.truststore.password = <password>>
smp.forceroot = true
smp.publicurl = https://toop-smp.brreg.no/
smp.pd.hostname = http://directory.acc.exchange.toop.eu/
sml.active = false
sml.needed = true
sml.smpid = TOOP-GBM-NO-1_changeme
sml.url = https://edelivery.tech.ec.europa.eu/edelivery-sml/manageparticipantidentifier
smp.peppol.directory.integration.enabled = true
smp.peppol.directory.hostname = https://directory.acc.exchange.toop.eu
smp.identifiertype = simple
smp.rest.type = bdxr
```

#### pd-client.properties
```
keystore.type = jks
keystore.path = /config/toop-keystore.jks
keystore.password = <password>
keystore.key.alias = brc
keystore.key.password = <password>
truststore.type = jks
truststore.path = /config/playground-truststore-v4.1.jks
truststore.password = <password>
```

#### webapp.properties
```
global.debug = false
global.production = true
webapp.datapath = conf
webapp.checkfileaccess = false
webapp.testversion = false
webapp.startpage.dynamictable = false
webapp.startpage.participants.none = false
webapp.startpage.extensions.show = false
webapp.directory.name = PEPPOL Directory
webapp.servicegroups.extensions.show = false
webapp.statistics.persist = false
```
(in addition, a 10MB persistent volume is mounted at /home/git/conf/ )

### For toop-connector
#### application.properties 
```
global.debug = false
global.production = true
global.instancename = no-breg-toop_changeme
toop.mem.implementation = phase4
toop.mem.incoming.url = http://toop-connector.brreg.no
toop.dsd.service.baseurl = http://dsd.dev.exchange.toop.eu
phase4.debug.http = false
phase4.debug.incoming = false
phase4.manager.inmemory = true
phase4.datapath = /tmp/
phase4.send.fromparty.id = brreg-no_changeme
phase4.keystore.type = pkcs12
phase4.keystore.path = /application/brc-2020.pfx
phase4.keystore.password = <password>
phase4.keystore.key-alias = brc
phase4.keystore.key-password = <password>
phase4.truststore.type = jks
phase4.truststore.path = /application/playground-truststore-v4.1.jks
phase4.truststore.password = <password>
truststore.type = jks
truststore.path = /application/playground-truststore-v4.1.jks
truststore.password = <password>
```

## Setting up TOOP (SMP and Connector)
- Log in to https://toop-smp.brreg.no/secure/ using [default username/password](https://github.com/phax/phoss-smp/wiki/Running#default-login)
- Change email and password for Administrator user at https://toop-smp.brreg.no/secure/locale-en_US/menuitem-admin_security_user
- Create a SML Configuration name="TOOP SMK", DNS Zone="toop.acc.edelivery.tech.ec.europa.eu", Management Service URL="https://acc.edelivery.tech.ec.europa.eu/edelivery-sml", Client Certificate Required=yes
- Register SML, using SML="TOOP SMK"
- Under SMP Settings, make sure everythin under SMK/SML and PEPPOL Directory is set to Yes (and Edit it if not)
- Under Certificate Information, verify that all keystores and truststores are present and include full certificate chain
- Under Business Cards, create a new Business Card and make sure it is pushed to PEPPOL Directory
- Under Service Groups, create a Service Group with Identifier Scheme="iso6523-actorid-upis" and Identifier Value="9999:norway2"
- Under Endpoints, create two endpoints (one for DP, one for DC)
    - DP: DocTypeId "toop-doctypeid-qns":"RegisteredOrganization::REGISTERED_ORGANIZATION_TYPE::CONCEPT##CCCEV::toop-edm:v2.0", ProcessId "toop-procid-agreement":"urn:eu.toop.process.dataquery", Transport "CEF AS4", Endpoint "https://toop-connector.brreg.no/phase4" and Certificate = a dump of the certificate
    - DC: DocTypeId "toop-doctypeid-qns":"QueryResponse::toop-edm:v2.0" (the rest is identical to DP)
       