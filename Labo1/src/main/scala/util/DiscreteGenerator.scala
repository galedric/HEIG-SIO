package util

import func.FunctionDefinition
import func.FunctionDefinition.Slice
import gen.RealizationGenerator
import java.util.ArrayDeque
import util.DiscreteGenerator.Law

/**
  * Objet companion de la classe DiscreteGenerator
  * Equivalent à des méthodes static en Java / C++
  */
object DiscreteGenerator {
	/**
	  * Une loi discrète est définie comme une séquence de réalisations possibles
	  * associées à des probabilités individuelles.
	  *
	  * @tparam T le type des réalisations de cette loi
	  */
	type Law[T] = Seq[(Double, T)]

	/**
	  * Fabrique un générateur discret sur la base d'une définition de fonction.
	  * Le générateur produira des réalisations qui sont des tranches de la fonction,
	  * avec des probabilités suivant la `sliceLaw` définie dans la fonction.
	  *
	  * @param fd     la définition de fonction à utiliser
	  * @param random la source d'aléatoire à utiliser
	  */
	def ofFunctionSlices(fd: FunctionDefinition)(implicit random: ExtendedRandom): DiscreteGenerator[Slice] =
		new DiscreteGenerator(fd.slicesLaw)
}

/**
  * Un générateur de réalisation d'une variable aléatoire discrète.
  *
  * L'implémentation est basée sur la méthode de l'alias qui permet la génération
  * d'une réalisation avec un temps constant.
  *
  * Une bonne explication de la méthode est disponible sur:
  * - http://www.keithschwarz.com/darts-dice-coins/
  *
  * Le même site propose également une implémentation Java de la méthode que j'ai
  * adapté pour Scala et réutilisé directement. Le code source est disponible sur:
  * - http://www.keithschwarz.com/interesting/code/?dir=alias-method
  *
  * Les commentaires sont recopiés à l'identique.
  */
class DiscreteGenerator[T](val law: Law[T])(implicit random: ExtendedRandom) extends RealizationGenerator[T] {
	if (!law.hasDefiniteSize) throw new IllegalArgumentException("The law table must have a finite length")

	// Split the law into a vector of probabilities and a vector of values
	var (probabilities, values) = law.toVector.unzip

	// The number of discrete values
	val n = values.length

	// Allocate space for the probability and alias tables.
	val probability = new Array[Double](n)
	val alias = new Array[Int](n)

	// Compute the average probability and cache it for later use.
	val average = 1.0 / n

	// Create two stacks to act as worklists as we populate the tables.
	val small, large = new ArrayDeque[Int]()

	// Populate the stacks with the input probabilities.
	for (i <- probabilities.indices) {
		// If the probability is below the average probability, then we add
		// it to the small list; otherwise we add it to the large list.
		if (probabilities(i) >= average) large.add(i) else small.add(i)
	}

	/* As a note: in the mathematical specification of the algorithm, we
	will always exhaust the small list before the big list.  However,
	due to floating point inaccuracies, this is not necessarily true.
	Consequently, this inner loop (which tries to pair small and large
	elements) will have to check that both lists aren't empty. */
	while (!small.isEmpty && !large.isEmpty) {
		// Get the index of the small and the large probabilities.
		val less = small.removeLast()
		val more = large.removeLast()

		// These probabilities have not yet been scaled up to be such
		// that 1/n is given weight 1.0.  We do this here instead.
		probability(less) = probabilities(less) * n
		alias(less) = more

		// Decrease the probability of the larger one by the appropriate amount.
		probabilities = probabilities.updated(more, (probabilities(more) + probabilities(less)) - average)

		// If the new probability is less than the average, add it into the
		// small list; otherwise add it to the large list.
		if (probabilities(more) >= average) large.add(more) else small.add(more)
	}

	/* At this point, everything is in one list, which means that the
	remaining probabilities should all be 1/n.  Based on this, set them
	appropriately.  Due to numerical issues, we can't be sure which
	stack will hold the entries, so we empty both. */
	while (!small.isEmpty) probability(small.removeLast()) = 1.0
	while (!large.isEmpty) probability(large.removeLast()) = 1.0

	def produce(): T = {
		// Generate a fair die roll to determine which column to inspect.
		val column = random.nextInt(n)

		// Generate a biased coin toss to determine which option to pick.
		val toss = random.nextDouble() < probability(column)

		// Based on the outcome, select either the column or its alias.
		val choice = if (toss) column else alias(column)

		// Return an actual value from the law
		values(choice)
	}
}
