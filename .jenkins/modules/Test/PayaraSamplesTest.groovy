sh """
mvn -V -B -ff clean install \
    --strict-checksums -Ppayara-server-remote \
    -Dpayara.version=${CFG.'build.version'} \
    -Dpayara.home=${pwd()}/${getPayaraDirectoryName(CFG.'build.version')} \
    -Dpayara.domain.name=${CFG.domain_name} \
    -Djavax.net.ssl.trustStore=${env.JAVA_HOME}/jre/lib/security/cacerts \
    -Djavax.xml.accessExternalSchema=all \
    -f appserver/tests/payara-samples """

