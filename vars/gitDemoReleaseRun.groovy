def ispwRelease             = ISPW_Application + Release_Number
def gitTagName              = "v" + Release_Number
def gitRepo                 = Owner_Id
def gitCredentials          = Jenkins_Git_Credentials
def continueRelease         = false

node
{
    currentBuild.displayName = ISPW_Application + "/" + Owner_Id + ", Release: " + ispwRelease + "/Tag: " + gitTagName
    
    stage("Create ISPW Release"){

        ispwOperation (
            connectionId:           Host_Connection, 
            credentialsId:          Jenkins_CES_Credentials, 
            consoleLogResponseBody: true,             
            ispwAction:             'CreateRelease', 
            ispwRequestBody: """
                runtimeConfiguration=ispw
                stream=GITDEMO1
                application=${ISPW_Application}
                releaseId=${ispwRelease}
                description=${ispwRelease}
            """
        )
    }
    
    stage("Transfer Tasks"){
        
        ispwOperation(
            connectionId:           Host_Connection, 
            credentialsId:          Jenkins_CES_Credentials, 
            consoleLogResponseBody: true,             
            ispwAction:             'TransferTask', 
            ispwRequestBody:        """
                runtimeConfiguration=ispw
                assignmentId=${ISPW_Assignment}
                level=MAIN
                containerId=${ispwRelease}
                containerType=R
            """
        )        

    }
    
    stage("Promote Release"){
        
        ispwOperation(
            connectionId:           Host_Connection, 
            credentialsId:          Jenkins_CES_Credentials, 
            consoleLogResponseBody: true,             
            ispwAction:             'PromoteRelease', 
            ispwRequestBody:        """
                runtimeConfiguration=ispw
                releaseId=${ispwRelease}
                level=MAIN                
            """
        )        

    }

    stage("Clone Repo"){
        
        checkout(
            changelog: false, 
            poll: false, 
            scm: [
                $class: 'GitSCM', 
                branches: [[name: '*/main']], 
                doGenerateSubmoduleConfigurations: false, 
                extensions: [], 
                submoduleCfg: [], 
                userRemoteConfigs: [
                    [
                        credentialsId: gitCredentials, 
                        url: "https://github.com/CPWRGIT/${gitRepo}.git"
                    ]
                ]
            ]
        )
    }
    
    stage("Add Tag"){
        
        def gitMessage = '"Release ' + gitTagName + '"'
        
        def stdOut = bat(
            label: '', 
            returnStdout: true, 
            script: 'git tag -a ' + gitTagName + ' -m ' + gitMessage
        )
        
        echo stdOut
        
        withCredentials(
            [
                usernamePassword(
                    credentialsId:      gitCredentials, 
                    passwordVariable:   'gitHubPassword', 
                    usernameVariable:   'gitHubUser'
                )
            ]
        ) 
        {

            stdOut = bat(
                returnStdout: true, 
                script: 'git remote set-url origin https://' + gitHubUser + ':' + gitHubPassword + "@github.com/CPWRGIT/${gitRepo}.git"
            )

            echo stdOut

            stdOut = bat(
                returnStdout: true, 
                script: "git push origin ${gitTagName}"
            )
    
            echo stdOut
            
        }
    }

    stage("Decide"){

        continueRelease = input(
            message: 'Uncheck box and click \n"Proceed" to fallback the release.\nOtherwise, leave the box checked and also click "Proceed" to resume the release', 
            parameters: [
                booleanParam(
                    defaultValue: true, 
                    description: '', name: 'continue Release'
                )
            ]
        )

    }

    if(continueRelease){

        stage("Close Release"){

            ispwOperation(
                connectionId:           Host_Connection, 
                credentialsId:          Jenkins_CES_Credentials, 
                consoleLogResponseBody: true,             
                ispwAction:             'CloseRelease', 
                ispwRequestBody:        """
                    runtimeConfiguration=ispw
                    releaseId=${ispwRelease}
                """
            )

        }
    }
    else
    {
        stage("Fallback Release"){

            ispwOperation(
                connectionId:           Host_Connection, 
                credentialsId:          Jenkins_CES_Credentials, 
                consoleLogResponseBody: true,             
                ispwAction:             'FallbackRelease', 
                ispwRequestBody:        """
                    runtimeConfiguration=ispw
                    releaseId=${ispwRelease}
                    level=PROD
                """
            )

        }

        stage("Clone Repo"){
            
            checkout(
                changelog: false, 
                poll: false, 
                scm: [
                    $class: 'GitSCM', 
                    branches: [[name: '*/main']], 
                    doGenerateSubmoduleConfigurations: false, 
                    extensions: [], 
                    submoduleCfg: [], 
                    userRemoteConfigs: [
                        [
                            credentialsId: gitCredentials, 
                            url: "https://github.com/CPWRGIT/${gitRepo}.git"
                        ]
                    ]
                ]
            )
        }

        stage("Remove Tag and Create Bugfix Branch"){
          
            withCredentials(
                [
                    usernamePassword(
                        credentialsId:      gitCredentials, 
                        passwordVariable:   'gitHubPassword', 
                        usernameVariable:   'gitHubUser'
                    )
                ]
            ) 
            {

                stdOut = bat(
                    returnStdout: true, 
                    script: '''
                        git reset --hard ''' + gitTagName + '''~
                        git remote set-url origin https://''' + gitHubUser + ''':''' + gitHubPassword + '''@github.com/CPWRGIT/''' + gitRepo + '''.git
                        git push --delete origin ''' + gitTagName + '''
                        git push origin -f
                        git branch bugfix/failed_''' + gitTagName + '''
                        git checkout checkout bugfix/failed_''' + gitTagName + '''
                        git push origin -f
                    '''
                )

                echo stdOut

            }
        }
    }
}