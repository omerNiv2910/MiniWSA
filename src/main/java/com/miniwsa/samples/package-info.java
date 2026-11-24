/**
 * Read side of the samples endpoint: the query ({@link com.miniwsa.samples.SampleQuery}), the response
 * ({@link com.miniwsa.samples.SampleResponse}) and the timeout error
 * ({@link com.miniwsa.samples.SamplesQueryTimeoutException}). The events are read by
 * {@link com.miniwsa.storage.EventStore#findSamples}.
 */
package com.miniwsa.samples;
