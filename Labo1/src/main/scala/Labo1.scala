import func.FunctionDefinition
import func.FunctionDefinition.Slice
import gen.{GeometricGenerator, HitMissGenerator, InverseGenerator}
import java.util.Random
import scala.annotation.tailrec
import util.{ExtendedRandom, MonteCarlo, StatsOps, Timer}

/**
  * Utilitaires de test liés au Labo1
  */
object Labo1 extends App {
	/**
	  * Construit un objet ExtendedRandom à partir d'un Random Java initialisé
	  * avec une graine spécifique.
	  */
	def defaultRandom: ExtendedRandom = new Random(42) with ExtendedRandom

	/**
	  * Calcul le ratio de l'aire de la fonction par rapport à l'aire du
	  * rectangle encadrant.
	  */
	def areaRatio(fd: FunctionDefinition): Double = fd.area / (fd.ym * (fd.b - fd.a))

	/**
	  * Fabrique aléatoirement une fonction correspondant aux paramètres demandés.
	  *
	  * @param n      nombre de points composant la fonction
	  * @param ymin   valeur minimale acceptable pour y
	  * @param ymax   valeur maximale acceptable pour y
	  * @param a      valeur de a
	  * @param b      valeur de b
	  * @param random le générateur d'aléatoire à utiliser
	  */
	@tailrec
	def craftFunction(n: Int, ymin: Int, ymax: Int, a: Int, b: Int)
	                 (implicit random: ExtendedRandom): FunctionDefinition = {
		// On s'assure que les paramètre soient sensés
		// - Au moins deux points
		// - YMax strictement positif
		// - Pas plus de points que ce qui est possible de placer
		if (n < 2 || ymax < 1 || b <= a || n > b - a + 1) throw new IllegalArgumentException

		// On génère un flux infini de valeur x potentielles
		// a et b sont garantis d'en faire partie
		val xs = Stream(a, b) ++ Stream.continually(random.nextInt(a, b))

		// Pour chaque x distinct dans l'ordre croissant, on construit une paire (x, y)
		// en lui associant une valeur y aléatoire.
		lazy val points = xs.distinct.take(n).sorted.map(x => (x, random.nextInt(ymin, ymax + 1)))

		// On vérifie que la fonction est valide (au moins un y non-nul)
		// Si ce n'est pas le cas, on recommence
		if (points.exists { case (_, y) => y != 0 }) FunctionDefinition(points)
		else craftFunction(n, ymin, ymax, a, b)
	}

	/**
	  * Fabrique aléatoirement une fonction correspondant aux paramètres demandés
	  * et s'assure que le ratio de l'aire de la fonction par rapport à celle du
	  * rectangle [a, b]x[0, ymax] soit dans les limites spécifiées.
	  *
	  * @param n      nombre de points composant la fonction
	  * @param ymin   valeur minimale acceptable pour y
	  * @param ymax   valeur maximale acceptable pour y
	  * @param a      valeur de a
	  * @param b      valeur de b
	  * @param rmin   borne minimale du ratio de l'aire de la fonction
	  * @param rmax   borne maximale du ratio de l'aire de la fonction
	  * @param random le générateur d'aléatoire à utiliser
	  */
	@tailrec
	def craftFunctionWithRatio(n: Int, ymin: Int, ymax: Int, a: Int, b: Int, rmin: Double, rmax: Double)
	                          (implicit random: ExtendedRandom): FunctionDefinition = {
		val fd = craftFunction(n, ymin, ymax, a, b)
		val ratio = areaRatio(fd)

		if (ratio >= rmin && ratio <= rmax) fd
		else craftFunctionWithRatio(n, ymin, ymax, a, b, rmin, rmax)
	}

	/**
	  * Fabrique aléatoirement une fonction adéquate avec un ratio d'aire donné.
	  */
	def craftSuitableFunction(ratio: Double): FunctionDefinition = {
		implicit val rand = new Random() with ExtendedRandom
		craftFunctionWithRatio(10, 0, 15, 0, 15, ratio - 0.05, ratio + 0.05)
	}

	/** Fabrique un générateur HitMiss associé à une instance de la source d'aléatoire par défaut */
	def hmg(fd: FunctionDefinition) = {
		implicit val rand = defaultRandom
		new HitMissGenerator(fd)
	}

	/** Fabrique un générateur Geometric associé à une instance de la source d'aléatoire par défaut */
	def geg(fd: FunctionDefinition) = {
		implicit val rand = defaultRandom
		new GeometricGenerator(fd)
	}

	/** Fabrique un générateur Inverse associé à une instance de la source d'aléatoire par défaut */
	def ing(fd: FunctionDefinition) = {
		implicit val rand = defaultRandom
		new InverseGenerator(fd)
	}

	/** Génère un nombre important de valeurs pour activer la compilation JIT de la JVM. */
	def warmup() = {
		println("Warming up...\n")

		val count = 1000000
		for (i <- 1 to 100) {
			val fd = craftSuitableFunction(0.5)
			hmg(fd).produce(count)
			geg(fd).produce(count)
			ing(fd).produce(count)
		}
	}

	/**
	  * Effectue l'analyse des trois générateurs sur la base d'une fonction spécifique.
	  *
	  * @param fd       la définition de la fonction à utiliser
	  * @param runs     le nombre d'itération à effectuer
	  * @param count    le nombre de valeurs à générer à chaque itération
	  * @param parallel indique si les trois générateurs doivent être testés en parallèle
	  */
	def benchmarkFunction(fd: FunctionDefinition, runs: Int = 10000, count: Int = 100000, parallel: Boolean = true) = {
		println("\nTesting function:")

		// Affichage des points composant la fonction
		val points = fd.slices.flatMap { case Slice(x0, y0, x1, y1) => Array((x0, y0), (x1, y1)) }.distinct
		println(points.mkString(", "))

		// Nombre d'itération et nombre de valeurs par itération
		println(s"\nSampling: $runs x $count")

		// Calcul de l'espérance mathématique et du ratio de l'aire de la fonction
		val mev = fd.expectedValue
		val ratio = areaRatio(fd)

		println("\nMathematical expected value: " + mev)
		println("Function area ratio: " + ratio + "\n")

		// Timer global pour le processus de benchmarking
		val timer = new Timer

		// La séquence de générateurs à tester
		val generators = Seq(hmg _, geg _, ing _)

		// Test de chaque générateur, séquentiellement ou parallèlement, et génération
		// d'une table de resultat textuelle.
		val res = for (gen_factory <- if (parallel) generators.par else generators) yield {
			// Instantiation du générateur
			val gen = gen_factory(fd)
			val name = gen.getClass.getSimpleName

			// Création du timer utilisé pour chaque itération
			val timer = new Timer

			// Execution des calculs
			val Seq(ev, time) = MonteCarlo.multirun(runs) {
				timer.reset()

				// Génération de `count` valeurs aléatoire à partir du générateur
				val realizations = gen.produce(count)

				// Enregistement du temps nécessaire
				val time = timer.time

				// Estimation de l'espérance en calculant la moyenne des valeurs générées
				val ev = realizations.mean

				Vector(ev, time)
			}

			// On vérifie si l'espérance mathématique est contenue dans l'intervalle de
			// confiance de l'espérance estimée
			val res = if (mev >= ev.ci.min && mev <= ev.ci.max) "OK" else "NOK"

			// Génération du tableau de résultats
			s"""$name: $res
				|         Stats |         Mean |      Std Dev |             CI              |     CI Delta
				|-----------------------------------------------------------------------------------------
				|Expected value | %12f | %12f | [%12f;%12f] | %12f
				|     Time [ms] | %12f | %12f | [%12f;%12f] | %12f
				""".stripMargin.format(
				ev.mean, ev.sd, ev.ci.min, ev.ci.max, ev.ci.delta,
				time.mean, time.sd, time.ci.min, time.ci.max, time.ci.delta
			)
		}

		// Affichage de chaque résultat séquentiellement
		res.seq.foreach(println _)

		// Affichage du temps total nécessaire pour les tests
		println("\nTime: " + timer.time / 1000.0 + " seconds")
	}
}
