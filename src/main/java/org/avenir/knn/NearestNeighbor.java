/*
 * avenir: Predictive analytic based on Hadoop Map Reduce
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.avenir.knn;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.SecondarySort;
import org.chombo.util.Tuple;
import org.chombo.util.Utility;

/**
 * KNN classifer
 * @author pranab
 *
 */
public class NearestNeighbor extends Configured implements Tool {

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Top n matches MR";
        job.setJobName(jobName);
        
        job.setJarByClass(NearestNeighbor.class);
        
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));
        
        job.setMapperClass(NearestNeighbor.TopMatchesMapper.class);
        job.setReducerClass(NearestNeighbor.TopMatchesReducer.class);
        
        job.setMapOutputKeyClass(Tuple.class);
        job.setMapOutputValueClass(Tuple.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        
        job.setGroupingComparatorClass(SecondarySort.TuplePairGroupComprator.class);
        job.setPartitionerClass(SecondarySort.TuplePairPartitioner.class);

        Utility.setConfiguration(job.getConfiguration());

        job.setNumReduceTasks(job.getConfiguration().getInt("num.reducer", 1));
        
        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}
	
	/**
	 * @author pranab
	 *
	 */
	public static class TopMatchesMapper extends Mapper<LongWritable, Text, Tuple, Tuple> {
		private String trainEntityId;
		private String testEntityId;
		private int rank;
		private Tuple outKey = new Tuple();
		private Tuple outVal = new Tuple();
        private String fieldDelimRegex;
        private String fieldDelim;
        private String trainClassAttr;
        private String testClassAttr;
        private boolean isValidationMode;

        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
           	fieldDelim = context.getConfiguration().get("field.delim", "\\[\\]");
            fieldDelimRegex = context.getConfiguration().get("field.delim.regex", "\\[\\]");
            isValidationMode = context.getConfiguration().getBoolean("validation.mode", true);
        }    

        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#map(KEYIN, VALUEIN, org.apache.hadoop.mapreduce.Mapper.Context)
         */
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            String[] items  =  value.toString().split(fieldDelimRegex);
            
            trainEntityId = items[0];
            testEntityId = items[1];
            rank = Integer.parseInt(items[2]);
            outKey.initialize();
            outKey.add(testEntityId, rank);
            outVal.initialize();
        	trainClassAttr = items[3];
            if (isValidationMode) {
            	//validation mode
            	testClassAttr = items[4];
                outKey.add(testEntityId, testClassAttr, rank);
            } else {
            	//prediction mode
                outKey.add(testEntityId, rank);
            }
        	outVal.add(trainEntityId,rank,trainClassAttr);
			context.write(outKey, outVal);
        }
	}
	
    /**
     * @author pranab
     *
     */
    public static class TopMatchesReducer extends Reducer<Tuple, Tuple, NullWritable, Text> {
    	private int topMatchCount;
		private String trainEntityId;
		private String testEntityId;
		private int count;
		private int distance;
		private String testClassValue;
		private Text outVal = new Text();
        private String fieldDelim;
        private boolean isValidationMode;
        private Neighborhood neighborhood;
        private String kernelFunction;
        private int kernelParam;
        
        
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Reducer#setup(org.apache.hadoop.mapreduce.Reducer.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
           	fieldDelim = config.get("field.delim", "\\[\\]");
        	topMatchCount = config.getInt("top.match.count", 10);
            isValidationMode = config.getBoolean("validation.mode", true);
            kernelFunction = config.get("kernel.function", "none");
        	kernelParam = config.getInt("kernel.param", -1);
        	neighborhood = new Neighborhood(kernelFunction, kernelParam);
        }
    	
    	/* (non-Javadoc)
    	 * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
    	 */
    	protected void reduce(Tuple key, Iterable<Tuple> values, Context context)
        	throws IOException, InterruptedException {
    		testEntityId  = key.getString(0);
    		count = 0;
        	for (Tuple value : values){
        		trainEntityId = value.getString(0);
        		distance = value.getInt(1);
        		testClassValue = value.getString(2);
        		neighborhood.addNeighbor(trainEntityId, distance, testClassValue);
        		if (++count == topMatchCount){
        			break;
        		}
			} 
    		if (isValidationMode) {
		        	//classify and compare
        	} else {
        		//classify
        	}
 			outVal.set("");
			context.write(NullWritable.get(), outVal);
    	}
    }
	
     
 
	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new NearestNeighbor(), args);
        System.exit(exitCode);
	}
}