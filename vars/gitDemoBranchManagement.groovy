def ispwUtLevel = "UT" + IspwTargetLevel.substring(2, 3)
def gitRepo     = "https://github.com/CPWRGIT/${HostUserId}.git"
def newBranchName

node {
    
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

            contextFileList.each {
                
                if (!(it.name.contains('Jenkins'))){
                    
                    echo "Reading: " + it.name
                    
                    def contextFileContent  = readFile(file: it.path)
                    contextFileContent      = contextFileContent.replace("FT1", IspwTargetLevel).replace("UT1", ispwUtLevel).replace("FT2", IspwTargetLevel).replace("UT2", ispwUtLevel).replace("FT3", IspwTargetLevel).replace("UT3", ispwUtLevel).replace("FT4", IspwTargetLevel).replace("UT4", ispwUtLevel)

                }
                
            }

            if (BranchType = "Feature")
            {
                newBranchName = 'feature/' + IspwTargetLevel + '/' + branchName
            }
            else{
                newBranchName = 'bugfix'
            }

            echo "Branch: " + newBranchName
        }
        else{
            echo "delete not implemented yet"
        }
    }
}