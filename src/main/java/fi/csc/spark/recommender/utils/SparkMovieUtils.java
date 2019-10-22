package fi.csc.spark.recommender.utils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.ml.recommendation.ALS;
import org.apache.spark.ml.recommendation.ALSModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.spark.MongoSpark;

import fi.csc.spark.recommender.models.Movie;
import fi.csc.spark.recommender.models.MovieRating;
import fi.csc.spark.recommender.models.MovieRecommendations;
import scala.Array;
import scala.Tuple3;
import scala.collection.Iterator;
import scala.collection.mutable.WrappedArray;

public final class SparkMovieUtils {

	
	private static ALS als;

	private static Broadcast<Map<Integer,String>> movieBroadcast;
	
	public static Broadcast<Map<Integer, String>> getMovieBroadcast() {
		return movieBroadcast;
	}

	public static void setMovieBroadcast(Broadcast<Map<Integer, String>> movieBroadcast) {
		SparkMovieUtils.movieBroadcast = movieBroadcast;
	}

	public static ALS getALS() {
		return als;
	}

	public static void setALS(ALS als) {
		SparkMovieUtils.als = als;

	}

	
	/*
	 * {'userId': 700, 'movieId': 1, 'rating': 5.0 }
	 */
	public static MovieRating getMovieRatingRecord(String json_message) {

		JsonParser parser = new JsonParser();
		JsonObject obj = (JsonObject) parser.parse(json_message);
		Integer userId = obj.get("userId").getAsInt();
		Integer movieId = obj.get("movieId").getAsInt();
		Double rating = obj.get("rating").getAsDouble();

		MovieRating mrating = new MovieRating();
		mrating.setUserId(userId);
		mrating.setMovieId(movieId);
		mrating.setRating(rating);

		return mrating;

	}

	public static void createModel() {

		ALS als = new ALS().setMaxIter(5).setRegParam(0.01).setUserCol("userId").setItemCol("movieId")
				.setRatingCol("rating");

		SparkMovieUtils.setALS(als);

	}

	
	public static MovieRecommendations transformMovieIdsToNames(Row row) {
		
		List<String> recommendationNames = new ArrayList<String>();
		Map<Integer,String> movieNames = SparkMovieUtils.getMovieBroadcast().getValue();
		
		Integer userId = (Integer)row.getAs("userId");
		@SuppressWarnings("unchecked")
		
		WrappedArray<GenericRowWithSchema> recommendationIDs = (WrappedArray<GenericRowWithSchema>) row.getAs("recommendations");
		
		
		Iterator<GenericRowWithSchema> it = recommendationIDs.iterator();
		int take = 0;
		while(it.hasNext()) {
			GenericRowWithSchema id_score  = it.next();
			recommendationNames.add(movieNames.get(id_score.get(0)));
			//if(take>10)
			//	break;
			//take++;
		}
		return new MovieRecommendations(userId, recommendationNames);
		
	}
	
	public static void mainFun(JavaRDD<MovieRating> rdd) {

		Dataset<Row> newRatingsDFRow = SparkUtils.getSparkSession().createDataFrame(rdd, MovieRating.class);
		Dataset<MovieRating> newRatingsDF = newRatingsDFRow.as(Encoders.bean(MovieRating.class));
		
		newRatingsDF.show();
				
		Dataset<MovieRating> originalRatingsDF = MongoSpark.load(SparkUtils.getSparkSession(), SparkMongoUtils.getRatingsReadConfig(), MovieRating.class);

		originalRatingsDF.show(10);
		
		Dataset<MovieRating> finalRatingsDF = newRatingsDF.union(originalRatingsDF);

		finalRatingsDF.show(10);
		ALSModel model = SparkMovieUtils.als.fit(finalRatingsDF);

		model.setColdStartStrategy("drop");


		Dataset<Row> usersDF = newRatingsDF.select("userId").distinct();
		Dataset<Row> usersRawRecommendations = model.recommendForUserSubset(usersDF, 10);
		
		usersRawRecommendations.show();
		
		
		Dataset<MovieRecommendations> usersRecommendations = usersRawRecommendations.map(
				(MapFunction<Row, MovieRecommendations>) row -> transformMovieIdsToNames(row), 
				Encoders.bean(MovieRecommendations.class));
		
		//System.out.println(usersRecommendations.collect());
		usersRecommendations.show(10);
    	//MongoSpark.save(usersRecommendations, SparkMongoUtils.getTestWriteConfig());
		//MongoSpark.save(usersRecommendations.write().option("collection", "test").mode("overwrite");
    	MongoSpark.save(usersRecommendations.write().mode("overwrite"), SparkMongoUtils.getTestWriteConfig());
    	System.out.println("Wrote the recommendations in MongoDB, now running the next round of spark streaming");

		 
	}

}
