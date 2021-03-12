def localBranchName
def consoleMessage
def ispwFtLevelRepl
def ispwUtLevelRepl
def gitRepo

node {

    stage("Initialize"){

        ispwFtLevelRepl = "." + IspwTargetLevel + "."
        ispwUtLevelRepl = ".UT" + IspwTargetLevel.substring(2, 3) + "."

        HostUserId          = HostUserId.toUpperCase()

        if(BranchAction == "<select>"){

            error "[ERROR] - You need to select a Branch Action. Aborting execution."

        }

        if (HostUserId == ''){

            error "[ERROR] - The Host User Id needs to be specified. Aborting execution."

        }

        if(BranchName == ''){

            error "[ERROR] - The Branch Name needs to be specified. Aborting execution."

        }

        if(GitHubUserName == ''){

            error "[ERROR] - The GitHub User Name needs to be specified. Aborting execution."

        }

        if(GitHubPassword == ''){

            error "[ERROR] - The GitHub Password needs to be specified. Aborting execution."

        }

        if(BranchAction == "Create"){

            if(BranchType == "Feature"){
                
                if(!(IspwTargetLevel.contains("FT"))){

                    error "[ERROR] - The Branch Name needs to be specified. Aborting execution."
                
                }
                else
                {
                
                    localBranchName = 'feature/' + IspwTargetLevel + '/' + BranchName
                
                }
            }
            else{
                
                localBranchName = 'bugfix'
            
            }
        }
        else{

            if(!(BranchName.contains('feature/FT'))){

                error "[ERROR] - For a DELETE Action you need to specify the full Branch Name."

            }
            else{

                localBranchName = branchName

            }

        }

        gitRepo         = "https://github.com/CPWRGIT/${HostUserId}.git"

        dir("./"){
            deleteDir()
        }
    }

    stage("Checkout"){

        def sourceBranchName

        if(BranchAction == "Create"){

            sourceBranchName = 'development'

        }
        else{

            sourceBranchName = localBranchName

        }

        checkout(
            changelog: false, 
            poll: false, 
            scm: [
                $class: 'GitSCM', 
                branches: [[name: '*/' + sourceBranchName]], 
                doGenerateSubmoduleConfigurations: false, 
                extensions: [[$class: 'LocalBranch', localBranch: localBranchName]],
                submoduleCfg: [], 
                userRemoteConfigs: [[url: gitRepo]]
            ]
        )
    }
    
    stage("Modify .context files"){

        if(BranchAction == 'Create'){

            def contextFileList = findFiles(glob: '**/Tests/**/*.context')

            if (BranchType == "Feature")
            {

                localBranchName = 'feature/' + IspwTargetLevel + '/' + BranchName

            }
            else{

                localBranchName = 'bugfix'

            }

            echo "[INFO] - Modifying .context files for branch " + localBranchName + "."
            echo "[INFO] - Setting load library UT and FT level qualifiers to " + ispwUtLevelRepl + " and " + ispwFtLevelRepl + "."

            contextFileList.each {
                
                if (!(it.name.contains('Jenkins'))){
                    
                    echo "[Info] - Modifying file: " + it.path
                    
                    def contextFileContent  = readFile(file: it.path)
                    contextFileContent      = contextFileContent.replace(".FT1.", ispwFtLevelRepl)
                    contextFileContent      = contextFileContent.replace(".UT1.", ispwUtLevelRepl)
                    contextFileContent      = contextFileContent.replace(".FT2.", ispwFtLevelRepl)
                    contextFileContent      = contextFileContent.replace(".UT2.", ispwUtLevelRepl)
                    contextFileContent      = contextFileContent.replace(".FT3.", ispwFtLevelRepl)
                    contextFileContent      = contextFileContent.replace(".UT3.", ispwUtLevelRepl)
                    contextFileContent      = contextFileContent.replace(".FT4.", ispwFtLevelRepl)
                    contextFileContent      = contextFileContent.replace(".UT4.", ispwUtLevelRepl)
                    contextFileContent      = contextFileContent.replace("<Value>UT1</Value>", "<Value>${ispwUtLevelRepl}</Value>")
                    contextFileContent      = contextFileContent.replace("<Value>UT2</Value>", "<Value>${ispwUtLevelRepl}</Value>")
                    contextFileContent      = contextFileContent.replace("<Value>UT3</Value>", "<Value>${ispwUtLevelRepl}</Value>")
                    contextFileContent      = contextFileContent.replace("<Value>UT4</Value>", "<Value>${ispwUtLevelRepl}</Value>")
                    contextFileContent      = contextFileContent.replace("<Value>FT1</Value>", "<Value>${ispwFtLevelRepl}</Value>")
                    contextFileContent      = contextFileContent.replace("<Value>FT2</Value>", "<Value>${ispwFtLevelRepl}</Value>")
                    contextFileContent      = contextFileContent.replace("<Value>FT3</Value>", "<Value>${ispwFtLevelRepl}</Value>")
                    contextFileContent      = contextFileContent.replace("<Value>FT4</Value>", "<Value>${ispwFtLevelRepl}</Value>")

                    writeFile(file: it.path, text: contextFileContent)
                }                
            }
        }
        else{

            echo "[INFO] - Deleting branch. No files to modify."

        }
    }

    stage("Branch Action"){

        if(BranchAction == 'Create'){

            dir("./")
            {
                echo "[INFO] - Creating branch " + localBranchName + " on remote repository."

                def message     = '"Inital Setup for Branch ' + localBranchName + '"'
                consoleMessage  = bat(returnStdout: true, script: 'git status')
                echo consoleMessage
                consoleMessage  = bat(returnStdout: true, script: 'git commit -a -m ' + message)
                echo consoleMessage
                consoleMessage  = bat(returnStdout: true, script: 'git push https://' + GitHubUserName + ':' + GitHubPassword + "@github.com/CPWRGIT/${HostUserId} refs/heads/${localBranchName}:refs/heads/${localBranchName} -f")
                echo consoleMessage
            }
        }
        else{

            dir("./")
            {
                echo "[INFO] - Deleting branch " + localBranchName + " from remote repository."

                def message     = '"Delete Branch ' + localBranchName + '"'
                consoleMessage  = bat(returnStdout: true, script: 'git push https://' + GitHubUserName + ':' + GitHubPassword + "@github.com/CPWRGIT/${HostUserId} --delete refs/heads/${localBranchName} -f")
                echo consoleMessage
            }


        }

    }
}