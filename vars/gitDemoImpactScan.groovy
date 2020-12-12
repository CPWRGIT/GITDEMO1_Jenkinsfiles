String synchConfigFile
String ispwImpactScanFile
String runtimeConfig
String hostCredentialsId

def call(Map pipelineParms)
{

    hostCredentialsId   = 'ea48408b-b2be-4810-8f4e-5b5f35977eb1'
    synchConfigFile     = './git2ispw/synchronization.yml'
    ispwImpactScanFile  = './git2ispw/impact_scan.jcl'
    runtimeConfig       = 'ispw'

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

        stage("Scan for Impacts"){

            topazSubmitFreeFormJcl(
                connectionId:       synchConfig.hciConnectionId, 
                credentialsId:      pipelineParms.hostCredentialsId, 
                jcl:                ispwImpactScanJcl, 
                maxConditionCode:   '4'
            )
        }

    }
}