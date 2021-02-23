currentBuild.displayName = "Close Release ${IspwApplication} - ${ReleaseId}"

node{
    dir(".\\") 
    {
        deleteDir()
    }

    stage("Release"){

        ispwOperation(
            connectionId:           '196de681-04d7-4170-824f-09a5457c5cda', 
            credentialsId:          CesCredentials,
            consoleLogResponseBody: true, 
            ispwAction:             'CloseRelease', 
            ispwRequestBody: """
                runtimeConfiguration=ispw
                releaseId=${ReleaseId}"""
        )
    }
    
}