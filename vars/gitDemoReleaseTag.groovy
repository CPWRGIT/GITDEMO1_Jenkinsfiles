def tagName = "v${ReleaseNumber}"

currentBuild.displayName = "Tagging ${IspwApplication} - ${tagName}"

node{
    
    dir("./"){
        
        deleteDir()
        
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
                        credentialsId: GitCredentials, 
                        url: "https://github.com/CPWRGIT/${GitRepo}.git"
                    ]
                ]
            ]
        )
    }
    
    stage("Add Tag"){
        
        def gitMessage = '"Release ' + tagName + '"'
        
        def stdOut = bat(
            label: '', 
            returnStdout: true, 
            script: 'git tag -a ' + tagName + ' -m ' + gitMessage
        )
        
        echo stdOut
        
        withCredentials(
            [
                usernamePassword(
                    credentialsId:      GitCredentials, 
                    passwordVariable:   'GitHubPassword', 
                    usernameVariable:   'GitHubUser'
                )
            ]
        ) 
        {

            stdOut = bat(
                returnStdout: true, 
                script: 'git remote set-url origin https://' + GitHubUser + ':' + GitHubPassword + '@github.com/CPWRGIT/${GitRepo}.git'
            )

            echo stdOut

            stdOut = bat(
                returnStdout: true, 
                script: "git push origin ${tagName}"
            )
    
            echo stdOut
            
        }
    }
}