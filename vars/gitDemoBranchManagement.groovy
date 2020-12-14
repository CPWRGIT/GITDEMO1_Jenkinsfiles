def ispwFtLevelRepl = "." + IspwTargetLevel + "."
def ispwUtLevelRepl = ".UT" + IspwTargetLevel.substring(2, 3) + "."

def gitRepo         = "https://github.com/CPWRGIT/${HostUserId}.git"
def newBranchName

HostUserId          = HostUserId.toUpperCase()

node {

    dir("./"){
        deleteDir()
    }

    stage("Checkout"){

        checkout(
            changelog: false, 
            poll: false, 
            scm: [
                $class: 'GitSCM', 
                branches: [[name: '*/development']], 
                doGenerateSubmoduleConfigurations: false, 
                extensions: [], 
                submoduleCfg: [], 
                userRemoteConfigs: [[url: gitRepo]]
            ]
        )
    }
    
    def contextFileList = findFiles(glob: '**/Tests/**/*.context')
    
    stage("Modify .context files"){

        if(BranchAction == 'Create'){

            if (BranchType == "Feature")
            {

                newBranchName = 'feature/' + IspwTargetLevel + '/' + branchName

            }
            else{

                newBranchName = 'bugfix'

            }

            echo "Branch: " + newBranchName

            dir("./")
            {

                bat(returnStdout: true, script: "git branch ${newBranchName}")
                
            }

            contextFileList.each {
                
                if (!(it.name.contains('Jenkins'))){
                    
                    echo "Modifying: " + it.path
                    
                    def contextFileContent  = readFile(file: it.path)
                    contextFileContent      = contextFileContent.replace(".FT1.", ispwFtLevelRepl).replace(".UT1.", ispwUtLevelRepl).replace(".FT2.", ispwFtLevelRepl).replace(".UT2.", ispwUtLevelRepl).replace(".FT3.", ispwFtLevelRepl).replace(".UT3.", ispwUtLevelRepl).replace(".FT4.", ispwFtLevelRepl).replace(".UT4.", ispwUtLevelRepl)
                    
                    writeFile(file: it.path, text: contextFileContent)
                }
                
            }
        }
        else{

            echo "delete not implemented yet"

        }
    }

    stage("Branch Action"){

        dir("./")
        {
            def message = '"Inital Setup new Branch"'
            bat(returnStdout: true, script: 'git status')
            bat(returnStdout: true, script: 'git commit -a -m ' + message)
            bat(returnStdout: true, script: "git push  https://${GitHubUserName}:${GitHubPassword}@github.com/CPWRGIT/${HostUserId} refs/heads/${newBranchName}:refs/heads/${newBranchName} -f")
        }

    }
}