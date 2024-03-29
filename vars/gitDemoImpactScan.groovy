String synchConfigFile
String ispwImpactScanFile
String runtimeConfig
String hostCredentialsId

def call(Map pipelineParms)
{

    hostCredentialsId   = 'ea48408b-b2be-4810-8f4e-5b5f35977eb1'
    synchConfigFile     = './git2ispw/synchronization.yml'
    ispwImpactScanFile  = './git2ispw/impact_scan.jcl'
    runtimeConfig       = 'ICCGA'

    node{

        def fileText    = libraryResource synchConfigFile
        synchConfig     = readYaml(text: fileText)

        //*********************************************************************************
        // Build JCL to scan for impacts once code has been loaded to the ISPW target level
        //*********************************************************************************

        ispwImpactScanJcl   = libraryResource ispwImpactScanFile

        ispwImpactScanJcl   = ispwImpactScanJcl.replace('<runtimeConfig>', runtimeConfig)
        ispwImpactScanJcl   = ispwImpactScanJcl.replace('<ispwApplication>', pipelineParms.ispwApplication)
        ispwImpactScanJcl   = ispwImpactScanJcl.replace('<ispwTargetLevel>', pipelineParms.ispwLevel)

        echo "Submitting: \n" + ispwImpactScanJcl

        echo "Using:"
        echo "Connection ID " + synchConfig.environment.hci.connectionId
        echo "Credentials ID " + hostCredentialsId

        stage("Scan for Impacts"){

            topazSubmitFreeFormJcl(
                connectionId:       synchConfig.environment.hci.connectionId, 
                credentialsId:      hostCredentialsId, 
                jcl:                ispwImpactScanJcl, 
                maxConditionCode:   '4'
            )
        }

    }
}