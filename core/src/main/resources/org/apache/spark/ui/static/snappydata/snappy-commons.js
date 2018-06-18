
/*
 * String utility function to check whether string is empty or whitespace only
 * or null or undefined
 *
 */
function isEmpty(str) {

  // Remove extra spaces
  str = str.replace(/\s+/g, ' ');

  switch (str) {
  case "":
  case " ":
  case null:
  case false:
  case typeof this == "undefined":
  case (/^\s*$/).test(str):
    return true;
  default:
    return false;
  }
}

/*
 * Utility function to check whether value is -1,
 * return true if -1 else false
 *
 */
function isNotApplicable(value) {

  if(!isNaN(value)){
    // if number, convert to string
    value = value.toString();
  }else{
    // Remove extra spaces
    value = value.replace(/\s+/g, ' ');
  }



  switch (value) {
  case "-1":
  case "-1.0":
  case "-1.00":
    return true;
  default:
    return false;
  }
}

/*
 * Utility function to apply Not Applicable constraint on value,
 * returns "NA" if isNotApplicable(value) returns true
 * else value itself
 *
 */
function applyNotApplicableCheck(value){
  if(isNotApplicable(value)){
    return "NA";
  }else{
    return value;
  }
}

/*
 * Utility function to convert given value in Bytes to KB or MB or GB or TB
 *
 */
function convertSizeToHumanReadable(value){
  // UNITS VALUES IN BYTES
  var ONE_KB = 1024;
  var ONE_MB = 1024 * 1024;
  var ONE_GB = 1024 * 1024 * 1024;
  var ONE_TB = 1024 * 1024 * 1024 * 1024;
  var ONE_PB = 1024 * 1024 * 1024 * 1024 * 1024;

  var convertedValue = new Array();
  var newValue = value;
  var newUnit = "B";

  if (value >= ONE_PB) {
      // Convert to PBs
      newValue = (value / ONE_PB);
      newUnit = "PB";
  } else if (value >= ONE_TB) {
    // Convert to TBs
    newValue = (value / ONE_TB);
    newUnit = "TB";
  } else if(value >= ONE_GB){
    // Convert to GBs
    newValue = (value / ONE_GB);
    newUnit = "GB";
  } else if(value >= ONE_MB){
    // Convert to MBs
    newValue = (value / ONE_MB);
    newUnit = "MB";
  } else if(value >= ONE_KB){
    // Convert to KBs
    newValue = (value / ONE_KB);
    newUnit = "KB";
  }

  // converted value
  convertedValue.push(newValue.toFixed(2));
  // B or KB or MB or GB or TB or PB
  convertedValue.push(newUnit);

  return convertedValue;
}

/*
 * An event handler function to handle error events occurred in AJAX request.
 *
 */
var ajaxRequestErrorHandler = function (jqXHR, status, error) {

  var displayMessage = "Could Not Fetch Statistics. <br>Reason: ";
  if (jqXHR.status == 401) {
    displayMessage += "Unauthorized Access.";
  } else if (jqXHR.status == 404) {
    displayMessage += "Server Not Found.";
  } else if (jqXHR.status == 408) {
    displayMessage += "Request Timeout.";
  } else if (jqXHR.status == 500) {
    displayMessage += "Internal Server Error.";
  } else if (jqXHR.status == 503) {
    displayMessage += "Service Unavailable.";
  }

  if (status === "timeout") {
    displayMessage += "Request Timeout.";
  } else if (status === "error") {
    displayMessage += "Error Occurred.";
  } else if (status === "abort") {
    displayMessage += "Request Aborted.";
  } else if (status === "parsererror") {
    displayMessage += "Parser Error.";
  } else {
    displayMessage += status + " : "+error;;
  }

  displayMessage += "<br>Please check lead logs to know more.";

  $("#AutoUpdateErrorMsg").html(displayMessage).show();
}
