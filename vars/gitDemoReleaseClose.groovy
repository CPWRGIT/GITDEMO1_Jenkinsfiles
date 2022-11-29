currentBuild.displayName = "Close Release ${IspwApplication} - ${ReleaseId}"

node{
    dir(".\\") 
    {
        deleteDir()
    }

    stage("Release"){

        ispwOperation(
            connectionId:           'de2ad7c3-e924-4dc2-84d5-d0c3afd3e756', 
            credentialsId:          CesCredentials,
            consoleLogResponseBody: true, 
            ispwAction:             'CloseRelease', 
            ispwRequestBody: """
                runtimeConfiguration=ispw
                releaseId=${ReleaseId}"""
        )
    }
    
}
