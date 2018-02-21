
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
 * Utility function to convert given value in Bytes to KB or MB or GB
 *
 */
function convertSizeToHumanReadable(value){
  // UNITS VALUES IN BYTES
  var ONE_KB = 1024;
  var ONE_MB = 1024 * 1024;
  var ONE_GB = 1024 * 1024 * 1024;

  var convertedValue = new Array();
  var valueInMBorGB = value;
  var isBorKBorMBorGB = "B";

  if (valueInMBorGB >= ONE_KB && valueInMBorGB < ONE_MB) {
    // Convert to KBs
    valueInMBorGB = (valueInMBorGB / ONE_KB);
    isBorKBorMBorGB = "KB";
  }else if(valueInMBorGB >= ONE_MB && valueInMBorGB < ONE_GB){
    // Convert to MBs
    valueInMBorGB = (valueInMBorGB / ONE_MB);
    isBorKBorMBorGB = "MB";
  }else if(valueInMBorGB >= ONE_GB ){
    // Convert to GBs
    valueInMBorGB = (valueInMBorGB / ONE_GB);
    isBorKBorMBorGB = "GB";
  }

  // converted value
  convertedValue.push(valueInMBorGB.toFixed(2));
  // B or KB or MB or GB
  convertedValue.push(isBorKBorMBorGB);

  return convertedValue;
}