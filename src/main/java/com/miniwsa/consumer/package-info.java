/**
 * Kafka consumers running in the same process: batch-consume raw events, enrich, write to storage, route poison records to the DLQ.
 */
package com.miniwsa.consumer;
