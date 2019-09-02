package com.compuware.devops.util
import groovy.json.JsonSlurper

/**
 Wrapper around the Sonar Qube activities and the Sonar Scanner
*/
class SonarHelper implements Serializable {

    def script
    def steps
    def scannerHome
    def pConfig
    def httpRequestAuthorizationHeader

    SonarHelper(script, steps, pConfig) 
    {
        this.script                         = script
        this.steps                          = steps
        this.pConfig                        = pConfig
    }

    /* A Groovy idiosynchrasy prevents constructors to use methods, therefore class might require an additional "initialize" method to initialize the class */
    def initialize()
    {
        this.scannerHome    = steps.tool "${pConfig.sqScannerName}";
    }

    def scan()
    {
        def testResults = determineUtResultPath()

        runScan(testResults, script.JOB_NAME)
    }

    def scan(pipelineType)
    {
        def project
        def testPath
        def resultPath
        def coveragePath

        switch(pipelineType)
        {
            case "UT":
                project         = determineProjectName("UT")
                testPath        = 'tests'
                resultPath      = determineUtResultPath()
                coveragePath    = "Coverage/CodeCoverage.xml"
                break;
            case "FT":
                project         = determineProjectName("FT")
                testPath        = '"tests\\' + pConfig.ispwStream + '_' + pConfig.ispwApplication + '_Functional_Tests\\Functional Test"'
                resultPath      = 'TestResults\\SonarTestReport.xml'
                coveragePath    = ''
                break;
            default:
                steps.echo "SonarHelper.scan received wrong pipelineType: " + pipelineType
                steps.echo "Valid types are 'UT' or FT"
                break;
        }

        runScan(testPath, resultPath, coveragePath, project)
    }

    def scan(Map scanParms)
    {
        def scanType            = ''
        def scanProject         = ''
        def scanTestPath        = ''
        def scanResultPath      = ''
        def scanCoveragePath    = ''

        scanType            = scanParms.scanType
        scanProject         = scanParms.scanProject

        if(scanProject == '')
        {
            scanProject = determineProjectName(scanType)
        }

        switch(scanType)
        {
            case 'initial':
                break:
            case "UT":
                scanTestPath        = 'tests'
                scanResultPath      = determineUtResultPath()
                scanCoveragePath    = "Coverage/CodeCoverage.xml"
                break;
            case "FT":
                scanTestPath        = '"tests\\' + pConfig.ispwStream + '_' + pConfig.ispwApplication + '_Functional_Tests\\Functional Test"'
                scanResultPath      = 'TestResults\\SonarTestReport.xml'
                scanCoveragePath    = ''
                break;
            default:
                steps.echo "SonarHelper.scan received wrong pipelineType: " + pipelineType
                steps.echo "Valid types are 'UT' or FT"
                break;
        }

        runScan(testPath, resultPath, coveragePath, project)
    }


    def initialScan(String projectName)
    {
        runScan('', '', '', projectName)
    }

    private runScan(testPath, testResultPath, coveragePath, projectName)
    {
        steps.withSonarQubeEnv("${pConfig.sqServerName}")       // Name of the SonarQube server defined in Jenkins / Configure Systems / SonarQube server section
        {
            // Build Sonar scanner parameters
            // Project Name and Key
            def sqScannerProperties     = " -Dsonar.projectKey=${projectName} -Dsonar.projectName=${projectName} -Dsonar.projectVersion=1.0" +
            // Folder(s) containing Mainframe sources downloaded from ISPW
                                          " -Dsonar.sources=${pConfig.ispwApplication}\\${pConfig.mfSourceFolder}" +
            // Folder(s) containing Mainframe copybooks
                                          " -Dsonar.cobol.copy.directories=${pConfig.ispwApplication}\\${pConfig.mfSourceFolder}" +
            // Suffixes to use for copybooks
                                          " -Dsonar.cobol.copy.suffixes=cpy" +
                                          " -Dsonar.sourceEncoding=UTF-8"

            // Building suffixes for main sources
            def sourceSuffixes          = " -Dsonar.cobol.file.suffixes=cbl"
            
            // Add parameters if tests and test result paths were passed as well
            if(testPath != '' && testResultPath != '')
            {
                sqScannerProperties     = sqScannerProperties + " -Dsonar.tests= ${testPath} -Dsonar.testExecutionReportPaths=${testResultPath}"

                sourceSuffixes          = sourceSuffixes + ",testsuite,testscenario,stub"
            }

            // Add parameters if code coverage paths were passed as well
            if(coveragePath != '')
            {
                sqScannerProperties       = sqScannerProperties + " -Dsonar.coverageReportPaths=${coveragePath}"
            }

            sqScannerProperties         = sqScannerProperties + sourceSuffixes
            
            // Call the SonarQube Scanner with properties defined above
            steps.bat "${scannerHome}/bin/sonar-scanner" + sqScannerProperties
        }
    }

    String checkQualityGate()
    {
        String result

        // Wait for the results of the SonarQube Quality Gate
        steps.timeout(time: 2, unit: 'MINUTES') 
        {                
            // Wait for webhook call back from SonarQube.  SonarQube webhook for callback to Jenkins must be configured on the SonarQube server.
            def sonarGate = steps.waitForQualityGate()

            result = sonarGate.status
        }

        return result
    }

    String determineProjectName(String projectType)
    {
        String projectName = ""

        switch(projectType)
        {
            case "UT":
                projectName = pConfig.ispwStream + '_' + pConfig.ispwApplication + '_' + pConfig.ispwAssignment + '_Unit_Tests'
                break;
            case "FT":
                projectName = pConfig.ispwStream + '_' + pConfig.ispwApplication + '_' + pConfig.ispwAssignment + '_Functional_Tests'
                break;
            case "Application":
                projectName = pConfig.ispwStream + '_' + pConfig.ispwApplication
                break;
            default:
                steps.echo "SonarHelper.scan received wrong projectType: " + projectType
                steps.echo "Valid types are 'Component', 'UT', 'FT' or 'Application'"
                break;
        }

        return projectName
        
    }

    private String determineUtResultPath()
    {
        // Finds all of the Total Test results files that will be submitted to SonarQube
        def tttListOfResults    = steps.findFiles(glob: 'TTTSonar/*.xml')   // Total Test SonarQube result files are stored in TTTSonar directory

        // Build the sonar testExecutionReportsPaths property
        // Start empty
        def testResults         = ""    

        // Loop through each result Total Test results file found
        tttListOfResults.each 
        {
            testResults         = testResults + "TTTSonar/" + it.name +  ',' // Append the results file to the property
        }

        return testResults
    }

    def checkForProject(String projectName)
    {
        def response

        def httpResponse = steps.httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: pConfig.sqHttpRequestAuthHeader]], 
            ignoreSslErrors:            true, 
            responseHandle:             'NONE', 
            consoleLogResponseBody:     true,
            url:                        "${pConfig.sqServerUrl}/api/projects/search?projects=${projectName}"

        def jsonSlurper = new JsonSlurper()
        def httpResp    = jsonSlurper.parseText(httpResponse.getContent())

        httpResponse    = null
        jsonSlurper     = null

        if(httpResp.message != null)
        {
            steps.echo "Resp: " + httpResp.message
            steps.error
        }
        else
        {
            // Compare the taskIds from the set to all tasks in the release 
            // Where they match, determine the assignment and add it to the list of assignments 
            def pagingInfo = httpResp.paging
            if(pagingInfo.total == 0)
            {
                response = "NOT FOUND"
            }
            else
            {
                response = "FOUND"
            }
        }

        return response
    }

    def createProject(String projectName)
    {
        def httpResponse = steps.httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: pConfig.sqHttpRequestAuthHeader]],
            httpMode:                   'POST',
            ignoreSslErrors:            true, 
            responseHandle:             'NONE', 
            consoleLogResponseBody:     true,
            url:                        "${pConfig.sqServerUrl}/api/projects/create?project=${projectName}&name=${projectName}"

        def jsonSlurper = new JsonSlurper()
        def httpResp    = jsonSlurper.parseText(httpResponse.getContent())
        
        httpResponse    = null
        jsonSlurper     = null

        if(httpResp.message != null)
        {
            steps.echo "Resp: " + httpResp.message
            steps.error
        }
        else
        {
            steps.echo "Created SonarQube project ${projectName}."
        }
    }

    def setQualityGate(String qualityGate, String projectName)
    {
        def qualityGateId = getQualityGateId(qualityGate)

        def httpResponse = steps.httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: pConfig.sqHttpRequestAuthHeader]],
            httpMode:                   'POST',
            ignoreSslErrors:            true, 
            responseHandle:             'NONE', 
            consoleLogResponseBody:     true,
            url:                        "${pConfig.sqServerUrl}/api/qualitygates/select?gateId=${qualityGateId}&projectKey=${projectName}"

        steps.echo "Assigned QualityGate ${qualityGate} to project ${projectName}."
    }

    private getQualityGateId(String qualityGateName)
    {
        def response

        def httpResponse = steps.httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: pConfig.sqHttpRequestAuthHeader]], 
            ignoreSslErrors:            true, 
            responseHandle:             'NONE', 
            consoleLogResponseBody:     true,
            url:                        "${pConfig.sqServerUrl}/api/qualitygates/show?name=${qualityGateName}"

        def jsonSlurper = new JsonSlurper()
        def httpResp    = jsonSlurper.parseText(httpResponse.getContent())

        httpResponse    = null
        jsonSlurper     = null

        if(httpResp.message != null)
        {
            steps.echo "Resp: " + httpResp.message
            steps.error
        }
        else
        {
            response = httpResp.id 
        }

        return response
    }
}
