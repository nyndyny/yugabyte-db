// tslint:disable
/**
 * Yugabyte Cloud
 * YugabyteDB as a Service
 *
 * The version of the OpenAPI document: v1
 * Contact: support@yugabyte.com
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */


// eslint-disable-next-line no-duplicate-imports
import type { AlertRuleInfo } from './AlertRuleInfo';
// eslint-disable-next-line no-duplicate-imports
import type { AlertRuleSpec } from './AlertRuleSpec';


/**
 * Alert rule data
 * @export
 * @interface AlertRuleData
 */
export interface AlertRuleData  {
  /**
   * 
   * @type {AlertRuleSpec}
   * @memberof AlertRuleData
   */
  spec: AlertRuleSpec;
  /**
   * 
   * @type {AlertRuleInfo}
   * @memberof AlertRuleData
   */
  info?: AlertRuleInfo;
}



