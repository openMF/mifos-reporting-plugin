# Mifos® Reporting Plugin (Eclipse BIRT®) for Apache Fineract®

## Overview

This is the **Eclipse BIRT (Business Intelligence and Reporting Tools)** of the Mifos® X Reporting Plugin for Apache Fineract®. It replaces the legacy Pentaho-based reporting system with a modern and lightweight reporting engine

## For Users

1. Create a directory for the Mifos® reports and copy the RPTDESIGN files in it 

```bash
    mkdir birtReports
```

2. Export the FINERACT_PENTAHO_REPORTS_PATH variable

```bash
    export FINERACT_BIRT_REPORTS_PATH="$PWD/birtReports/"
```    

3. Download the Mifos® Security Plugin and extract the files (all the libraries required for running it are included). **It is very important to use the specific version according to the Apache Fineract**

| Apache Fineract | Mifos Reporting Plugin | Download Link |
| :---:         |     :---:      |          :---: |
| TBD   | TBD     | TBD    |


4a. Execute only for Docker® - Create a directory, copy the Mifos® BIRT Plugin and the Eclipse BIRT libraries in it

```bash
    mkdir fineract-birt  && cd fineract-birt
```

4b. Execute only for Apache Tomcat® - Copy the Mifos® BIRT Plugin and Eclipse BIRT libraries in $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/

5. Restart Docker® or Apache Tomcat®

6. Test the Mifos® reports.

## For Developers

This project is currently only tested against the very latest and greatest bleeding edge Apache Fineract® `develop` branch on Linux Ubuntu® 24.04LTS. 
Building and using it against other Apache Fineract® versions may be possible, but is not tested or documented here.

1. Download and compile

```bash
    git clone https://github.com/openMF/mifos-x-reporting-plugin.git
    cd mifos-x-reporting-plugin && ./mvnw -Dmaven.test.skip=true clean package && cd ..
```
2. Export the Location of Mifos® reports (RPTDESIGN files) in the following variable

```bash
    export FINERACT_BIRT_REPORTS_PATH="$PWD/birtReports/"
```   

3. Execute Apache Fineract® with the location of the Mifos® BIRT Plugin library

```bash
java -Dloader.path=$MIFOS_BIRT_PLUGIN_HOME/libs/ -jar $APACHE_FINERACT_HOME/fineract-provider.jar
```

4. Test the Mifos® reports execution using the following curl example or through the Mifos Web App in the Reports Menu

```bash
    curl --location --request GET 'https://localhost:8443/fineract-provider/api/v1/runreports/Active%20Loans%20-%20Details?tenantIdentifier=default&locale=en&dateFormat=dd%20MMMM%20yyyy&R_startDate=01%20January%202022&R_endDate=02%20January%202023&R_officeId=1&output-type=PDF&R_loanOfficerId=-1' \
--header 'Fineract-Platform-TenantId: default' \
--header 'Authorization: Basic bWlmb3M6cGFzc3dvcmQ='
```


5. The output must be a PDF with the Active Loans Details information in it (maybe it could have blank or zeroes if it is a fresh Apache Fineract® setup)
![alt text](https://github.com/openMF/fineract-pentaho/blob/1.8/img/screenshot_pentaho_report.png?raw=true)

The API call (above) should not fail if you follow the steps as shown, and all conditions met for the version of Apache Fineract®

If the API call (above) fails, then this BIRT® Plugin has not been correctly registered & loaded by Apache Fineract®.

Please note that the library will work using the latest Apache Fineract® development branch, also make sure you got installed the type fonts required by the reports. This Mifos® Reporting Plugin will work only on Apache Tomcat® version 10+.

See also [`BirtReportingProcessServiceImplTest`](src/test/java/org/apache/fineract/infrastructure/report/service/BirtReportingProcessServiceImplTest.java) and the [`test`](test) script.

## License

The code and report templates in this git repo itself are
[licensed to you under the Mozilla® Public License 2.0 (MPL)](https://github.com/openMF/mifos-x-reporting-plugin/blob/dev/LICENSE).

## Important

* Mifos® and Mifos® Reporting Plugin are not affiliated with, endorsed by, or otherwise associated with the Apache Software Foundation® (ASF) or any of its projects.
* Apache Software Foundation® is a vendor-neutral organization and it is an important part of the brand is that Apache Software Foundation® (ASF) projects are governed independently.
* Apache Fineract®, Fineract, Apache, the Apache® feather, and the Apache Fineract® project logo are either registered trademarks or trademarks of the Apache Software Foundation®.
* Mifos® and Mifos® Reporting Plugin are not affiliated with, endorsed by, or otherwise associated with Eclipse Foundation or any of its projects.
* Eclipse® and Eclipse BIRT® and the associated logos are registered trademarks of the Eclipse Foundation, Inc.. The trademarks are used to identify the open-source business intelligence and reporting project hosted by the Eclipse Foundation.

## Contribute

If this Mifos® Reporting Plugin project is useful to you, please contribute back to it by raising Pull Requests yourself with any enhancements you make, and by helping to maintain this project by helping other users on Issues and reviewing PR from others (you will be promoted to committer on this project when you contribute).  
We recommend that you _Watch_ and _Star_ this project on GitHub® to make it easy to get notified.
